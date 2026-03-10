package mayson.profileservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mayson.profileservice.vo.AssistantAskRequestVO;
import mayson.profileservice.vo.AssistantAskResponseVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssistantService {

    private static final String DEFAULT_PROVIDER = "local-fallback";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;

    public AssistantService(
            ObjectMapper objectMapper,
            @Value("${assistant.base-url:}") String baseUrl,
            @Value("${assistant.api-key:}") String apiKey,
            @Value("${assistant.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${assistant.timeout-seconds:25}") int timeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.timeoutSeconds = timeoutSeconds <= 0 ? 25 : timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(this.timeoutSeconds, 10)))
                .build();
    }

    public AssistantAskResponseVO ask(AssistantAskRequestVO request) {
        String question = request == null || request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isBlank()) {
            throw new IllegalArgumentException("Question cannot be empty.");
        }

        if (canUseModel()) {
            try {
                String answer = askModel(question, safe(request.getContext()), safe(request.getCurrency()), safe(request.getTimezone()));
                if (!answer.isBlank()) {
                    return AssistantAskResponseVO.builder()
                            .answer(answer)
                            .modelUsed(true)
                            .provider("openai-compatible")
                            .build();
                }
            } catch (Exception ignored) {
                // Deterministic fallback below keeps chat usable if model provider fails.
            }
        }

        return AssistantAskResponseVO.builder()
                .answer(fallbackAnswer(question, safe(request.getContext()), safe(request.getCurrency())))
                .modelUsed(false)
                .provider(DEFAULT_PROVIDER)
                .build();
    }

    private boolean canUseModel() {
        return !baseUrl.isBlank();
    }

    private String askModel(String question, String context, String currency, String timezone) throws Exception {
        String prompt = buildPrompt(question, context, currency, timezone);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.2);
        payload.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content", "You are Mayson Finance AI. Use only the provided user finance context. " +
                                "Give clear answers with exact numbers from context if available. If data is missing, say it."
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

    private String buildPrompt(String question, String context, String currency, String timezone) {
        return "QUESTION:\n" + question + "\n\n" +
                "USER_CURRENCY: " + (currency.isBlank() ? "unknown" : currency) + "\n" +
                "USER_TIMEZONE: " + (timezone.isBlank() ? "unknown" : timezone) + "\n\n" +
                "USER_DATA_CONTEXT:\n" + context + "\n\n" +
                "Rules:\n" +
                "- Use concise English.\n" +
                "- Prefer bullet points for comparisons.\n" +
                "- Never invent values not present in context.\n" +
                "- If needed data is missing, ask one specific follow-up question.";
    }

    private String fallbackAnswer(String question, String context, String currency) {
        String lower = question.toLowerCase();
        String currentCurrency = currency == null || currency.isBlank() ? "MXN" : currency;
        if (lower.contains("most expensive") && lower.contains("week")) {
            return "I could not reach the model provider right now, but based on your weekly context: " +
                    "check `Weekly most expensive expense` and `Weekly most expensive receipt item` in the data block. " +
                    "I can answer this exactly once AI provider credentials are configured.";
        }
        if (lower.contains("safe spend")) {
            return "Model is temporarily unavailable. Use `Safe spend today` from your dashboard context (already in " + currentCurrency + ").";
        }
        if (context.isBlank()) {
            return "I need your latest dashboard context to answer reliably. Refresh dashboard data and ask again.";
        }
        return "AI model is currently unavailable, but your question is captured. " +
                "Enable `assistant.base-url` (and optionally `assistant.api-key`) in profile-service to get full generative answers.";
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.endsWith("/")) return value.substring(0, value.length() - 1);
        return value;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
