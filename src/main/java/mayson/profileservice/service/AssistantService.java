package mayson.profileservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mayson.profileservice.jpa.AssistantMemory;
import mayson.profileservice.repository.AssistantMemoryRepository;
import mayson.profileservice.vo.AssistantAskRequestVO;
import mayson.profileservice.vo.AssistantAskResponseVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final String DEFAULT_PROVIDER = "local-fallback";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_MAX_CONTEXT_CHARS = 120000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AssistantMemoryRepository assistantMemoryRepository;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final int maxContextChars;
    private final long cacheTtlMillis;
    private final Map<String, CacheEntry> responseCache = new ConcurrentHashMap<>();

    public AssistantService(
            ObjectMapper objectMapper,
            AssistantMemoryRepository assistantMemoryRepository,
            @Value("${assistant.base-url:}") String baseUrl,
            @Value("${assistant.api-key:}") String apiKey,
            @Value("${assistant.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${assistant.timeout-seconds:25}") int timeoutSeconds,
            @Value("${assistant.cache-ttl-seconds:120}") int cacheTtlSeconds,
            @Value("${assistant.max-context-chars:" + DEFAULT_MAX_CONTEXT_CHARS + "}") int maxContextChars
    ) {
        this.objectMapper = objectMapper;
        this.assistantMemoryRepository = assistantMemoryRepository;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.timeoutSeconds = timeoutSeconds <= 0 ? 25 : timeoutSeconds;
        this.maxContextChars = maxContextChars <= 2000 ? DEFAULT_MAX_CONTEXT_CHARS : maxContextChars;
        this.cacheTtlMillis = Math.max(30, cacheTtlSeconds) * 1000L;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(this.timeoutSeconds, 10)))
                .build();
    }

    public AssistantAskResponseVO ask(String userId, AssistantAskRequestVO request) {
        String question = request == null || request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isBlank()) {
            throw new IllegalArgumentException("Question cannot be empty.");
        }

        String context = capContext(safe(request.getContext()));
        String currency = safe(request.getCurrency());
        String timezone = safe(request.getTimezone());
        AssistantMemory memory = loadMemory(userId);

        String cacheKey = buildCacheKey(userId, question, context, currency, timezone);
        CacheEntry cached = responseCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            return AssistantAskResponseVO.builder()
                    .answer(cached.answer)
                    .modelUsed(cached.modelUsed)
                    .provider(cached.provider)
                    .build();
        }

        if (canUseModel()) {
            try {
                String answer = askModel(question, context, currency, timezone, memory);
                if (!answer.isBlank()) {
                    updateMemory(userId, memory, question, answer, currency);
                    AssistantAskResponseVO response = AssistantAskResponseVO.builder()
                            .answer(answer)
                            .modelUsed(true)
                            .provider("openai-compatible")
                            .build();
                    responseCache.put(cacheKey, new CacheEntry(response.getAnswer(), response.isModelUsed(), response.getProvider(), now + cacheTtlMillis));
                    return response;
                }
            } catch (Exception firstError) {
                log.warn("Assistant model call failed, retrying once. reason={}", firstError.getMessage());
                try {
                    String retryAnswer = askModel(question, context, currency, timezone, memory);
                    if (!retryAnswer.isBlank()) {
                        updateMemory(userId, memory, question, retryAnswer, currency);
                        AssistantAskResponseVO response = AssistantAskResponseVO.builder()
                                .answer(retryAnswer)
                                .modelUsed(true)
                                .provider("openai-compatible")
                                .build();
                        responseCache.put(cacheKey, new CacheEntry(response.getAnswer(), response.isModelUsed(), response.getProvider(), now + cacheTtlMillis));
                        return response;
                    }
                } catch (Exception retryError) {
                    log.warn("Assistant model retry failed. reason={}", retryError.getMessage());
                }
            }
        }

        String fallback = fallbackAnswer(question, context, currency);
        updateMemory(userId, memory, question, fallback, currency);
        AssistantAskResponseVO response = AssistantAskResponseVO.builder()
                .answer(fallback)
                .modelUsed(false)
                .provider(DEFAULT_PROVIDER)
                .build();
        responseCache.put(cacheKey, new CacheEntry(response.getAnswer(), response.isModelUsed(), response.getProvider(), now + cacheTtlMillis));
        return response;
    }

    private boolean canUseModel() {
        return !baseUrl.isBlank();
    }

    private String askModel(String question, String context, String currency, String timezone, AssistantMemory memory) throws Exception {
        String prompt = buildPrompt(question, context, currency, timezone, memory);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.2);
        payload.put("max_tokens", 260);
        payload.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content", "You are Mayson Finance AI. Use only provided user data context and memory. " +
                                "Answer with practical, concrete guidance and exact values when available."
                ),
                Map.of("role", "user", "content", prompt)
        ));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        if (!apiKey.isBlank()) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        HttpRequest httpRequest = builder.build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Model provider returned HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("Model provider returned empty answer.");
        }
        return content.asText().trim();
    }

    private String buildPrompt(String question, String context, String currency, String timezone, AssistantMemory memory) {
        return "QUESTION:\n" + question + "\n\n" +
                "USER_CURRENCY: " + (currency.isBlank() ? "unknown" : currency) + "\n" +
                "USER_TIMEZONE: " + (timezone.isBlank() ? "unknown" : timezone) + "\n" +
                "USER_MEMORY_TOPICS: " + safe(memory.getLastTopics()) + "\n" +
                "USER_MEMORY_PREFERENCES: " + safe(memory.getPreferenceSummary()) + "\n\n" +
                "USER_DATA_CONTEXT:\n" + context + "\n\n" +
                "Rules:\n" +
                "- Always provide a short direct answer first.\n" +
                "- Then provide 3 actionable bullets with quantified impact when possible.\n" +
                "- If question is about saving money, include a 'Savings Plan' with daily + weekly guardrails.\n" +
                "- Never invent values not present in context.\n" +
                "- If key data is missing, ask one concrete follow-up question.";
    }

    private String fallbackAnswer(String question, String context, String currency) {
        String lower = question.toLowerCase();
        String currentCurrency = currency == null || currency.isBlank() ? "MXN" : currency;
        if (lower.contains("save") || lower.contains("sparen")) {
            double safeSpend = readContextNumber(context, "Safe spend today:");
            double weekTotal = readContextNumber(context, "Week total:");
            double weekGoal = readContextNumber(context, "Goal per week:");
            double gap = weekGoal > 0 ? Math.max(0, weekGoal - weekTotal) : 0;
            return "Savings Plan\n" +
                    "- Keep daily spend at or below " + format2(safeSpend) + " " + currentCurrency + ".\n" +
                    "- Remaining weekly budget buffer: " + format2(gap) + " " + currentCurrency + ".\n" +
                    "- Cut unusual_weekly first, then travel extras; keep usual_weekly stable.";
        }
        if (lower.contains("most expensive") && lower.contains("week")) {
            return "I could not reach the model provider right now, but based on your weekly context: " +
                    "check `Weekly most expensive expense` and `Weekly most expensive receipt item` in the data block.";
        }
        if (lower.contains("safe spend")) {
            return "Model is temporarily unavailable. Use `Safe spend today` from your dashboard context (already in " + currentCurrency + ").";
        }
        if (context.isBlank()) {
            return "I need your latest dashboard context to answer reliably. Refresh dashboard data and ask again.";
        }
        return "AI model is currently unavailable, but your question is captured. " +
                "Assistant will keep using your saved memory and context when provider is reachable.";
    }

    private AssistantMemory loadMemory(String userId) {
        if (userId == null || userId.isBlank()) {
            return AssistantMemory.builder().userId("anonymous").build();
        }
        return assistantMemoryRepository.findByUserId(userId)
                .orElseGet(() -> AssistantMemory.builder()
                        .userId(userId)
                        .preferenceSummary("")
                        .lastTopics("")
                        .updatedAt(LocalDateTime.now())
                        .build());
    }

    private void updateMemory(String userId, AssistantMemory memory, String question, String answer, String currency) {
        if (userId == null || userId.isBlank()) return;
        Set<String> topics = new LinkedHashSet<>();
        String mergedText = (safe(memory.getLastTopics()) + " " + question + " " + answer).toLowerCase();
        if (mergedText.contains("save") || mergedText.contains("sparen")) topics.add("saving");
        if (mergedText.contains("travel")) topics.add("travel");
        if (mergedText.contains("usual_weekly")) topics.add("usual_weekly");
        if (mergedText.contains("subscription") || mergedText.contains("recurring")) topics.add("recurring");
        if (mergedText.contains("shopping") || mergedText.contains("receipt")) topics.add("shopping");

        String preferences = safe(memory.getPreferenceSummary());
        if (question.toLowerCase().contains("short")) {
            preferences = appendPreference(preferences, "prefers_short_answers");
        }
        if (!safe(currency).isBlank()) {
            preferences = appendPreference(preferences, "currency=" + currency.toUpperCase());
        }

        AssistantMemory next = AssistantMemory.builder()
                .id(memory.getId())
                .userId(userId)
                .preferenceSummary(truncate(preferences, 800))
                .lastTopics(truncate(String.join(",", topics), 500))
                .updatedAt(LocalDateTime.now())
                .build();
        assistantMemoryRepository.save(next);
    }

    private String appendPreference(String base, String value) {
        if (base == null || base.isBlank()) return value;
        if (base.contains(value)) return base;
        return base + ";" + value;
    }

    private String buildCacheKey(String userId, String question, String context, String currency, String timezone) {
        String raw = safe(userId) + "|" + question.toLowerCase(Locale.ROOT) + "|" + currency + "|" + timezone + "|" + Integer.toHexString(context.hashCode());
        return Integer.toHexString(raw.hashCode());
    }

    private double readContextNumber(String context, String prefix) {
        if (context == null || context.isBlank()) return 0;
        for (String line : context.split("\\n")) {
            if (!line.startsWith(prefix)) continue;
            String rest = line.substring(prefix.length()).trim();
            try {
                return Double.parseDouble(rest.split("\\s+")[0]);
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String format2(double value) {
        if (!Double.isFinite(value)) return "0.00";
        return String.format(Locale.US, "%.2f", value);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.endsWith("/")) return value.substring(0, value.length() - 1);
        return value;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String capContext(String context) {
        if (context == null) return "";
        if (context.length() <= maxContextChars) return context;
        return context.substring(0, maxContextChars);
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        if (value.length() <= maxLen) return value;
        return value.substring(0, maxLen);
    }

    private static class CacheEntry {
        private final String answer;
        private final boolean modelUsed;
        private final String provider;
        private final long expiresAt;

        private CacheEntry(String answer, boolean modelUsed, String provider, long expiresAt) {
            this.answer = answer;
            this.modelUsed = modelUsed;
            this.provider = provider;
            this.expiresAt = expiresAt;
        }
    }
}
