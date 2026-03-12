package mayson.profileservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mayson.profileservice.jpa.ShoppingReceipt;
import mayson.profileservice.jpa.ShoppingReceiptItem;
import mayson.profileservice.repository.ShoppingReceiptItemRepository;
import mayson.profileservice.repository.ShoppingReceiptRepository;
import mayson.profileservice.vo.ShoppingAnalyticsVO;
import mayson.profileservice.vo.ShoppingCategoryStatVO;
import mayson.profileservice.vo.ShoppingReceiptItemVO;
import mayson.profileservice.vo.ShoppingReceiptVO;
import mayson.profileservice.vo.ShoppingTopProductVO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

@Service
public class ShoppingReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ShoppingReceiptService.class);
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String DEFAULT_RECEIPT_AI_MODEL = "llava:7b";
    private static final Pattern MONEY_TOKEN_PATTERN = Pattern.compile("(?<!\\d)(?:MXN|USD|EUR|\\$|€)?\\s*([0-9]{1,4}(?:[\\.,][0-9]{3})*(?:[\\.,][0-9]{2})|[0-9]+[\\.,][0-9]{2})(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(".*\\b(total|subtotal|tax|iva|cambio|change|cash|visa|mastercard|payment|pago|tendered|balance|rounding|discount|descuento|propina|tip)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY_PREFIX_PATTERN = Pattern.compile("^\\s*\\d+[xX]\\s+.*");
    private static final Pattern BULK_PREFIX_PATTERN = Pattern.compile("^\\s*(?:kg|g|gr|ml|l|lt|pcs|pc)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECEIPT_CODE_LINE_PATTERN = Pattern.compile("^\\s*[A-Z0-9\\-_/]{8,}\\s*$");
    private static final Pattern RECEIPT_NOISE_PATTERN = Pattern.compile(".*\\b(autoriz|aprobada|afiliacion|tarjeta|cajero|suc\\.?|terminal|operator|operador|cambios|encuesta|folio|debito|credito|banc|mastercard|visa)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECEIPT_DISCOUNT_PATTERN = Pattern.compile(".*\\b(slps|ahorro|descuento|discount|promo)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY_ITEM_TWO_PRICES_PATTERN = Pattern.compile("^\\s*\\d+[\\.,]?\\d*\\s+(.+?)\\s+([0-9]+[\\.,][0-9]{2})\\s+([0-9]+[\\.,][0-9]{2})(?:\\s+[A-Z])?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_ONE_PRICE_PATTERN = Pattern.compile("^\\s*(.+?)\\s+\\$?\\s*([0-9]+[\\.,][0-9]{2})\\s*-?\\s*$", Pattern.CASE_INSENSITIVE);

    private final ShoppingReceiptRepository receiptRepository;
    private final ShoppingReceiptItemRepository itemRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String receiptAiBaseUrl;
    private final String receiptAiApiKey;
    private final String receiptAiModel;
    private final int receiptAiTimeoutSeconds;

    public ShoppingReceiptService(
            ShoppingReceiptRepository receiptRepository,
            ShoppingReceiptItemRepository itemRepository,
            ObjectMapper objectMapper,
            @Value("${receipt-ai.base-url:${assistant.base-url:}}") String receiptAiBaseUrl,
            @Value("${receipt-ai.api-key:${assistant.api-key:}}") String receiptAiApiKey,
            @Value("${receipt-ai.model:}") String receiptAiModel,
            @Value("${receipt-ai.timeout-seconds:${assistant.timeout-seconds:45}}") int receiptAiTimeoutSeconds
    ) {
        this.receiptRepository = receiptRepository;
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
        this.receiptAiBaseUrl = receiptAiBaseUrl == null ? "" : receiptAiBaseUrl.trim();
        this.receiptAiApiKey = receiptAiApiKey == null ? "" : receiptAiApiKey.trim();
        this.receiptAiModel = receiptAiModel == null || receiptAiModel.isBlank()
                ? DEFAULT_RECEIPT_AI_MODEL
                : receiptAiModel.trim();
        this.receiptAiTimeoutSeconds = Math.max(20, receiptAiTimeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(this.receiptAiTimeoutSeconds, 10)))
                .build();
    }

    @Transactional
    public ShoppingReceiptVO uploadReceipt(String userId, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Upload is empty");
        }

        String name = image.getOriginalFilename() == null ? "upload" : image.getOriginalFilename();
        if (!isSupportedFile(name, image.getContentType())) {
            throw new IllegalArgumentException("Unsupported file type. Please upload image or PDF.");
        }

        ShoppingReceipt created = receiptRepository.save(ShoppingReceipt.builder()
                .userId(userId)
                .status(STATUS_PROCESSING)
                .storeName("Processing")
                .currency("USD")
                .totalAmount(BigDecimal.ZERO)
                .originalFileName(trim(name, 255))
                .savedAsExpense(false)
                .isSupermarketPurchase(false)
                .inferredCategory("unusual_weekly")
                .createdAt(LocalDateTime.now())
                .build());

        try {
            byte[] content = image.getBytes();
            CompletableFuture.runAsync(() -> processReceiptAsync(created.getId(), userId, name, content));
        } catch (IOException ioException) {
            created.setStatus(STATUS_FAILED);
            created.setErrorMessage(trim("Could not read uploaded file", 600));
            created.setProcessedAt(LocalDateTime.now());
            receiptRepository.save(created);
        }

        return toVO(created, List.of());
    }

    public List<ShoppingReceiptVO> listReceipts(String userId) {
        return receiptRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toVOWithItems)
                .toList();
    }

    public ShoppingReceiptVO getReceipt(String userId, Long receiptId) {
        ShoppingReceipt receipt = receiptRepository.findByIdAndUserId(receiptId, userId)
                .orElseThrow(() -> new RuntimeException("Receipt not found"));
        return toVOWithItems(receipt);
    }

    @Transactional
    public void deleteReceipt(String userId, Long receiptId) {
        ShoppingReceipt receipt = receiptRepository.findByIdAndUserId(receiptId, userId)
                .orElseThrow(() -> new RuntimeException("Receipt not found"));
        itemRepository.deleteAllByReceipt(receipt);
        receiptRepository.delete(receipt);
    }

    @Transactional
    public ShoppingReceiptVO markSavedAsExpense(String userId, Long receiptId) {
        ShoppingReceipt receipt = receiptRepository.findByIdAndUserId(receiptId, userId)
                .orElseThrow(() -> new RuntimeException("Receipt not found"));
        receipt.setSavedAsExpense(true);
        return toVOWithItems(receiptRepository.save(receipt));
    }

    @Transactional
    public ShoppingReceiptVO updateSupermarketClassification(String userId, Long receiptId, boolean supermarketPurchase) {
        ShoppingReceipt receipt = receiptRepository.findByIdAndUserId(receiptId, userId)
                .orElseThrow(() -> new RuntimeException("Receipt not found"));
        receipt.setIsSupermarketPurchase(supermarketPurchase);
        if (supermarketPurchase) {
            receipt.setInferredCategory("usual_weekly");
        } else {
            List<ParsedItem> parsed = itemRepository.findAllByReceiptOrderByPositionIndexAsc(receipt).stream()
                    .map(item -> new ParsedItem(item.getItemName(), item.getItemPrice(), safe(item.getItemConfidence())))
                    .toList();
            receipt.setInferredCategory(inferCategory(receipt.getRawText(), parsed, receipt.getStoreName()));
        }
        return toVOWithItems(receiptRepository.save(receipt));
    }

    public ShoppingAnalyticsVO getAnalytics(String userId) {
        List<ShoppingReceipt> completed = receiptRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(receipt -> STATUS_COMPLETED.equals(receipt.getStatus()))
                .toList();

        if (completed.isEmpty()) {
            return ShoppingAnalyticsVO.builder()
                    .averageProductsPerWeek(BigDecimal.ZERO)
                    .averageSpendPerWeek(BigDecimal.ZERO)
                    .currentWeekProducts(BigDecimal.ZERO)
                    .currentWeekSpend(BigDecimal.ZERO)
                    .currentWeekReceipts(0L)
                    .topProducts(List.of())
                    .categoryBreakdown(List.of())
                    .build();
        }

        LocalDate now = LocalDate.now();
        LocalDate oldest = completed.stream()
                .map(receipt -> receipt.getCreatedAt().toLocalDate())
                .min(Comparator.naturalOrder())
                .orElse(now);
        long weeksObserved = Math.max(1, java.time.temporal.ChronoUnit.WEEKS.between(startOfWeek(oldest), startOfWeek(now)) + 1);

        LocalDate weekStart = startOfWeek(now);
        BigDecimal currentWeekProducts = BigDecimal.ZERO;
        BigDecimal currentWeekSpend = BigDecimal.ZERO;
        long currentWeekReceipts = 0;

        BigDecimal totalProducts = BigDecimal.ZERO;
        BigDecimal totalSpend = BigDecimal.ZERO;
        Map<String, Long> categoryCounts = new HashMap<>();
        Map<String, ProductAggregate> productMap = new HashMap<>();

        for (ShoppingReceipt receipt : completed) {
            List<ShoppingReceiptItem> items = itemRepository.findAllByReceiptOrderByPositionIndexAsc(receipt);
            BigDecimal itemCount = BigDecimal.valueOf(items.size());
            BigDecimal spend = safe(receipt.getTotalAmount());
            totalProducts = totalProducts.add(itemCount);
            totalSpend = totalSpend.add(spend);
            categoryCounts.merge(normalizeCategory(receipt.getInferredCategory()), 1L, Long::sum);

            boolean includeForTop = Boolean.TRUE.equals(receipt.getIsSupermarketPurchase())
                    || "usual_weekly".equalsIgnoreCase(receipt.getInferredCategory());
            if (includeForTop) {
                for (ShoppingReceiptItem item : items) {
                    String normalized = normalizeProductName(item.getItemName());
                    if (normalized.length() < 2) {
                        continue;
                    }
                    ProductAggregate aggregate = productMap.computeIfAbsent(normalized, key -> new ProductAggregate());
                    aggregate.addSample(safe(item.getItemPrice()), receipt.getCreatedAt().toLocalDate());
                }
            }

            if (!receipt.getCreatedAt().toLocalDate().isBefore(weekStart)) {
                currentWeekProducts = currentWeekProducts.add(itemCount);
                currentWeekSpend = currentWeekSpend.add(spend);
                currentWeekReceipts += 1;
            }
        }

        List<ShoppingTopProductVO> topProducts = productMap.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().purchaseCount, a.getValue().purchaseCount))
                .limit(8)
                .map(entry -> {
                    ProductAggregate aggregate = entry.getValue();
                    BigDecimal avgPrice = aggregate.purchaseCount <= 0
                            ? BigDecimal.ZERO
                            : aggregate.totalPrice.divide(BigDecimal.valueOf(aggregate.purchaseCount), 2, RoundingMode.HALF_UP);
                    BigDecimal trendPct = aggregate.averageHistoricalPrice.compareTo(BigDecimal.ZERO) > 0
                            ? aggregate.lastPrice.subtract(aggregate.averageHistoricalPrice)
                                .divide(aggregate.averageHistoricalPrice, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                            : BigDecimal.ZERO;
                    int estNextDays = aggregate.averageIntervalDays == null
                            ? 7
                            : Math.max(1, Math.min(30, aggregate.averageIntervalDays));
                    return ShoppingTopProductVO.builder()
                            .name(entry.getKey())
                            .purchaseCount(aggregate.purchaseCount)
                            .avgUnitsPerWeek(divide(BigDecimal.valueOf(aggregate.purchaseCount), weeksObserved))
                            .avgSpendPerWeek(divide(aggregate.totalPrice, weeksObserved))
                            .avgPrice(avgPrice)
                            .lastPrice(aggregate.lastPrice)
                            .priceTrendPct(trendPct.setScale(2, RoundingMode.HALF_UP))
                            .estimatedNextPurchaseDays(estNextDays)
                            .lastPurchasedAt(aggregate.lastPurchaseDate)
                            .build();
                })
                .toList();

        List<ShoppingCategoryStatVO> categories = categoryCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(entry -> ShoppingCategoryStatVO.builder()
                        .category(entry.getKey())
                        .receipts(entry.getValue())
                        .build())
                .collect(Collectors.toList());

        return ShoppingAnalyticsVO.builder()
                .averageProductsPerWeek(divide(totalProducts, weeksObserved))
                .averageSpendPerWeek(divide(totalSpend, weeksObserved))
                .currentWeekProducts(currentWeekProducts.setScale(2, RoundingMode.HALF_UP))
                .currentWeekSpend(currentWeekSpend.setScale(2, RoundingMode.HALF_UP))
                .currentWeekReceipts(currentWeekReceipts)
                .topProducts(topProducts)
                .categoryBreakdown(categories)
                .build();
    }

    public void processReceiptAsync(Long receiptId, String userId, String originalFileName, byte[] content) {
        ShoppingReceipt receipt = receiptRepository.findByIdAndUserId(receiptId, userId)
                .orElseThrow(() -> new RuntimeException("Receipt not found"));

        try {
            String text = "";
            RuntimeException extractionError = null;
            try {
                text = extractText(content, originalFileName);
            } catch (RuntimeException ex) {
                extractionError = ex;
                log.warn("OCR text extraction failed for receiptId={}: {}", receiptId, ex.getMessage());
            } catch (Exception ex) {
                extractionError = new RuntimeException(ex.getMessage(), ex);
                log.warn("OCR text extraction failed for receiptId={}: {}", receiptId, ex.getMessage());
            }

            VisionExtraction vision = extractWithVision(content, originalFileName);
            List<ParsedItem> ocrItems = parseItems(text);
            List<ParsedItem> parsedItems = selectBestItems(ocrItems, vision.items());
            BigDecimal ocrTotal = parseTotal(text, ocrItems);
            BigDecimal selectedTotal = parseTotal(text, parsedItems);
            BigDecimal parsedTotal = chooseParsedTotal(ocrTotal, selectedTotal, vision.total(), ocrItems, vision.items(), parsedItems);
            BigDecimal itemSum = parsedItems.stream()
                    .map(ParsedItem::price)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal total = chooseMostPlausibleTotal(parsedTotal, itemSum, parsedItems.size());
            if (total.compareTo(BigDecimal.ZERO) <= 0 && parsedItems.isEmpty()) {
                if (extractionError != null && !vision.hasSignal()) {
                    throw extractionError;
                }
                throw new RuntimeException("Could not extract a valid receipt. Please upload a clearer image/PDF.");
            }
            String currency = vision.currency().isBlank() ? detectCurrency(text) : vision.currency();
            String storeName = vision.storeName().isBlank() ? detectStore(text) : vision.storeName();
            String rawCorpus = mergeRawCorpus(text, vision.rawText());
            String inferredCategory = inferCategory(rawCorpus, parsedItems, storeName);
            boolean supermarketPurchase = "usual_weekly".equalsIgnoreCase(inferredCategory);

            receipt.setStatus(STATUS_COMPLETED);
            receipt.setStoreName(trim(storeName, 180));
            receipt.setCurrency(currency);
            receipt.setTotalAmount(total);
            receipt.setInferredCategory(inferredCategory);
            receipt.setIsSupermarketPurchase(supermarketPurchase);
            receipt.setRawText(trim(rawCorpus, 40000));
            receipt.setErrorMessage(null);
            receipt.setProcessedAt(LocalDateTime.now());
            receipt.setSavedAsExpense(Boolean.TRUE.equals(receipt.getSavedAsExpense()));
            receipt = receiptRepository.save(receipt);

            itemRepository.deleteAllByReceipt(receipt);
            List<ShoppingReceiptItem> entities = new ArrayList<>();
            for (int i = 0; i < parsedItems.size(); i++) {
                ParsedItem parsed = parsedItems.get(i);
                entities.add(ShoppingReceiptItem.builder()
                        .receipt(receipt)
                        .positionIndex(i)
                        .itemName(trim(parsed.name(), 220))
                        .itemPrice(parsed.price())
                        .itemConfidence(parsed.confidence())
                        .build());
            }
            itemRepository.saveAll(entities);
        } catch (Exception exception) {
            receipt.setStatus(STATUS_FAILED);
            receipt.setStoreName("Processing failed");
            receipt.setErrorMessage(trim(exception.getMessage(), 600));
            receipt.setProcessedAt(LocalDateTime.now());
            receiptRepository.save(receipt);
            itemRepository.deleteAllByReceipt(receipt);
        }
    }

    private VisionExtraction extractWithVision(byte[] content, String originalFileName) {
        if (!canUseReceiptAi()) {
            return VisionExtraction.empty();
        }
        try {
            List<VisionImage> images = buildVisionImages(content, originalFileName);
            if (images.isEmpty()) {
                return VisionExtraction.empty();
            }

            List<Map<String, Object>> contentParts = new ArrayList<>();
            contentParts.add(Map.of(
                    "type", "text",
                    "text", """
                            Extract this shopping receipt and return STRICT JSON only.
                            Schema:
                            {
                              "storeName": "string",
                              "currency": "MXN|USD|EUR|OTHER",
                              "total": "number string with 2 decimals",
                              "items": [{"name":"string","price":"number string with 2 decimals"}]
                            }
                            Rules:
                            - Include every recognized line item with a positive price.
                            - Keep names clean and short.
                            - If uncertain, still return best-effort values.
                            - Never include markdown or explanation, return JSON object only.
                            """
            ));
            for (VisionImage image : images) {
                contentParts.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", "data:" + image.mimeType() + ";base64," + image.base64())
                ));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", receiptAiModel);
            payload.put("temperature", 0.0);
            payload.put("max_tokens", 1200);
            payload.put("messages", List.of(
                    Map.of(
                            "role", "system",
                            "content", "You extract receipts into structured JSON with high recall and precision."
                    ),
                    Map.of("role", "user", "content", contentParts)
            ));

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(receiptAiBaseUrl) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(receiptAiTimeoutSeconds))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            if (!receiptAiApiKey.isBlank()) {
                requestBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer " + receiptAiApiKey);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Receipt AI provider HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String modelText = root.path("choices").path(0).path("message").path("content").asText("");
            String jsonObject = extractJsonObject(modelText);
            if (jsonObject.isBlank()) {
                return VisionExtraction.empty();
            }

            JsonNode parsed = objectMapper.readTree(jsonObject);
            String storeName = trim(parsed.path("storeName").asText(""), 180);
            String currency = normalizeCurrency(parsed.path("currency").asText(""));
            BigDecimal total = toMoney(parsed.path("total").asText(""));
            List<ParsedItem> items = parseVisionItems(parsed.path("items"));
            return new VisionExtraction(
                    storeName == null ? "" : storeName,
                    currency,
                    safe(total).setScale(2, RoundingMode.HALF_UP),
                    items,
                    trim(jsonObject, 12000)
            );
        } catch (Exception ex) {
            log.warn("Receipt AI extraction failed: {}", ex.getMessage());
            return VisionExtraction.empty();
        }
    }

    private String extractText(byte[] content, String originalFileName) throws Exception {
        if (isPdf(originalFileName)) {
            return extractTextFromPdf(content);
        }
        return extractTextFromImage(content, originalFileName);
    }

    private String extractTextFromPdf(byte[] content) throws Exception {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFRenderer renderer = new PDFRenderer(document);
            StringBuilder builder = new StringBuilder();
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                String pageText = extractPageText(document, page);
                if (stripToAlnum(pageText).length() >= 20) {
                    builder.append(pageText).append("\n");
                    continue;
                }
                BufferedImage image = renderer.renderImageWithDPI(page, 240f, ImageType.RGB);
                byte[] pngBytes = toPng(image);
                builder.append(extractTextFromImage(pngBytes, "pdf-page.png")).append("\n");
            }
            return builder.toString();
        }
    }

    private String extractPageText(PDDocument document, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(document);
    }

    private byte[] toPng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }

    private String extractTextFromImage(byte[] imageBytes, String originalFileName) throws Exception {
        Path tempImage = Files.createTempFile("mayson-receipt-", ".png");
        try {
            BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
            String lowerName = originalFileName == null ? "" : originalFileName.toLowerCase(Locale.ROOT);
            if (source == null && (lowerName.endsWith(".heic") || lowerName.endsWith(".heif"))) {
                byte[] converted = convertHeicToPng(imageBytes, lowerName);
                if (converted != null) {
                    source = ImageIO.read(new java.io.ByteArrayInputStream(converted));
                }
            }
            if (source != null) {
                if (source.getWidth() < 8 || source.getHeight() < 8) {
                    throw new RuntimeException("Image is too small for extraction. Please upload a higher-resolution image.");
                }
                BufferedImage prepared = preprocessForOcr(source);
                ImageIO.write(prepared, "png", tempImage.toFile());
            } else {
                if (lowerName.endsWith(".heic") || lowerName.endsWith(".heif")) {
                    throw new RuntimeException("HEIC image could not be converted for OCR. Please upload JPG/PNG or PDF.");
                }
                throw new RuntimeException("Unsupported image encoding for OCR. Please upload JPG, PNG, WEBP or PDF.");
            }

            String firstPass = runTesseract(tempImage, "6");
            if (firstPass.toLowerCase(Locale.ROOT).contains("image too small to scale")) {
                throw new RuntimeException("Image is too small for extraction. Please upload a higher-resolution image.");
            }
            if (stripToAlnum(firstPass).length() >= 20) {
                return firstPass;
            }
            String secondPass = runTesseract(tempImage, "11");
            if (secondPass.toLowerCase(Locale.ROOT).contains("image too small to scale")) {
                throw new RuntimeException("Image is too small for extraction. Please upload a higher-resolution image.");
            }
            return secondPass.length() > firstPass.length() ? secondPass : firstPass;
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }

    private String runTesseract(Path imagePath, String psm) throws Exception {
        Process process = new ProcessBuilder(
                "tesseract",
                imagePath.toString(),
                "stdout",
                "-l", "eng+spa",
                "--oem", "1",
                "--psm", psm,
                "-c", "preserve_interword_spaces=1",
                "-c", "user_defined_dpi=300"
        ).redirectErrorStream(true).start();

        String output;
        try (InputStream inputStream = process.getInputStream()) {
            output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("OCR failed. Please upload a clearer image/PDF.");
        }
        return output == null ? "" : output;
    }

    private BufferedImage preprocessForOcr(BufferedImage source) {
        int targetWidth = source.getWidth();
        int targetHeight = source.getHeight();
        if (source.getWidth() < 1600) {
            double scale = 1600.0 / source.getWidth();
            targetWidth = 1600;
            targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        }

        BufferedImage gray = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();

        // Lightweight contrast normalization for receipt text
        for (int y = 0; y < gray.getHeight(); y++) {
            for (int x = 0; x < gray.getWidth(); x++) {
                int rgb = gray.getRGB(x, y) & 0xFF;
                int boosted = rgb < 140 ? Math.max(0, rgb - 35) : Math.min(255, rgb + 25);
                int bin = boosted < 155 ? 0 : 255;
                int out = (bin << 16) | (bin << 8) | bin;
                gray.setRGB(x, y, out);
            }
        }
        return gray;
    }

    private String stripToAlnum(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "");
    }

    private boolean isSupportedFile(String fileName, String contentType) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
                || type.startsWith("image/") || type.contains("pdf");
    }

    private boolean isPdf(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private boolean canUseReceiptAi() {
        return !receiptAiBaseUrl.isBlank() && !receiptAiModel.isBlank();
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private List<VisionImage> buildVisionImages(byte[] content, String originalFileName) throws Exception {
        List<VisionImage> images = new ArrayList<>();
        if (isPdf(originalFileName)) {
            try (PDDocument document = Loader.loadPDF(content)) {
                PDFRenderer renderer = new PDFRenderer(document);
                int pageCount = Math.min(2, document.getNumberOfPages());
                for (int page = 0; page < pageCount; page++) {
                    BufferedImage pageImage = renderer.renderImageWithDPI(page, 220f, ImageType.RGB);
                    BufferedImage prepared = preprocessForVision(pageImage);
                    byte[] png = toPng(prepared);
                    images.add(new VisionImage("image/png", Base64.getEncoder().encodeToString(png)));
                }
            }
            return images;
        }

        BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(content));
        if (source == null) {
            String lowerName = originalFileName == null ? "" : originalFileName.toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".heic") || lowerName.endsWith(".heif")) {
                byte[] converted = convertHeicToPng(content, lowerName);
                if (converted != null) {
                    images.add(new VisionImage("image/png", Base64.getEncoder().encodeToString(converted)));
                    return images;
                }
            }
            String fallbackMime = guessMimeTypeFromName(originalFileName);
            images.add(new VisionImage(fallbackMime, Base64.getEncoder().encodeToString(content)));
            return images;
        }

        BufferedImage prepared = preprocessForVision(source);
        byte[] png = toPng(prepared);
        images.add(new VisionImage("image/png", Base64.getEncoder().encodeToString(png)));
        return images;
    }

    private BufferedImage preprocessForVision(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int maxWidth = 1400;
        if (width <= maxWidth) {
            return source;
        }
        double scale = (double) maxWidth / width;
        int targetWidth = maxWidth;
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return resized;
    }

    private String guessMimeTypeFromName(String originalFileName) {
        if (originalFileName == null) {
            return "image/png";
        }
        String lower = originalFileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) {
            return "image/heic";
        }
        return "image/png";
    }

    private byte[] convertHeicToPng(byte[] imageBytes, String originalFileName) {
        String lower = originalFileName == null ? "" : originalFileName.toLowerCase(Locale.ROOT);
        String suffix = lower.endsWith(".heif") ? ".heif" : ".heic";
        Path inputFile = null;
        Path outputFile = null;
        try {
            inputFile = Files.createTempFile("mayson-heic-", suffix);
            outputFile = Files.createTempFile("mayson-heic-converted-", ".png");
            Files.write(inputFile, imageBytes);

            List<List<String>> commands = List.of(
                    List.of("magick", inputFile.toString(), "-auto-orient", "-strip", outputFile.toString()),
                    List.of("convert", inputFile.toString(), "-auto-orient", "-strip", outputFile.toString()),
                    List.of("heif-convert", inputFile.toString(), outputFile.toString())
            );
            for (List<String> command : commands) {
                int exit = runExternalCommand(command, 35);
                if (exit == 0 && Files.exists(outputFile) && Files.size(outputFile) > 0) {
                    return Files.readAllBytes(outputFile);
                }
            }
            return null;
        } catch (Exception ex) {
            log.warn("HEIC conversion failed: {}", ex.getMessage());
            return null;
        } finally {
            try {
                if (inputFile != null) Files.deleteIfExists(inputFile);
            } catch (Exception ignored) { }
            try {
                if (outputFile != null) Files.deleteIfExists(outputFile);
            } catch (Exception ignored) { }
        }
    }

    private int runExternalCommand(List<String> command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream inputStream = process.getInputStream()) {
                output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Command timed out ({}): {}s", String.join(" ", command), timeoutSeconds);
                return -1;
            }
            int exit = process.exitValue();
            if (exit != 0) {
                log.warn("Command failed ({}): {}", String.join(" ", command), trim(output, 300));
            }
            return exit;
        } catch (Exception ex) {
            log.warn("Command not available ({}): {}", String.join(" ", command), ex.getMessage());
            return -1;
        }
    }

    private List<ParsedItem> parseVisionItems(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Map<String, ParsedItem> unique = new LinkedHashMap<>();
        for (JsonNode itemNode : node) {
            String name = cleanupItemName(itemNode.path("name").asText(""));
            BigDecimal price = toMoney(itemNode.path("price").asText(""));
            if (name.length() < 2 || price.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal confidence = BigDecimal.valueOf(0.75).setScale(4, RoundingMode.HALF_UP);
            String key = name.toLowerCase(Locale.ROOT) + "|" + price.toPlainString();
            unique.putIfAbsent(key, new ParsedItem(name, price, confidence));
        }
        return new ArrayList<>(unique.values());
    }

    private List<ParsedItem> selectBestItems(List<ParsedItem> ocrItems, List<ParsedItem> visionItems) {
        List<ParsedItem> ocr = ocrItems == null ? List.of() : ocrItems;
        List<ParsedItem> vision = visionItems == null ? List.of() : visionItems;

        if (vision.isEmpty()) return ocr;
        if (ocr.isEmpty()) return vision;

        double ocrQuality = scoreItemListQuality(ocr);
        double visionQuality = scoreItemListQuality(vision);
        if (ocrQuality >= visionQuality + 0.08) {
            return ocr;
        }
        if (visionQuality >= ocrQuality + 0.12 && vision.size() >= 3) {
            return vision;
        }
        return mergeItemsPreferOcr(ocr, vision);
    }

    private double scoreItemListQuality(List<ParsedItem> items) {
        if (items == null || items.isEmpty()) {
            return 0.0;
        }
        double avgConfidence = items.stream()
                .map(ParsedItem::confidence)
                .mapToDouble(conf -> safe(conf).doubleValue())
                .average()
                .orElse(0.0);
        long longNames = items.stream().map(ParsedItem::name).filter(name -> name != null && name.length() >= 4).count();
        long validPriceScale = items.stream().map(ParsedItem::price).filter(price -> safe(price).scale() >= 2).count();
        double diversity = Math.min(1.0, items.size() / 8.0);
        return (avgConfidence * 0.5)
                + (((double) longNames / items.size()) * 0.2)
                + (((double) validPriceScale / items.size()) * 0.2)
                + (diversity * 0.1);
    }

    private List<ParsedItem> mergeItemsPreferOcr(List<ParsedItem> ocrItems, List<ParsedItem> visionItems) {
        Map<String, ParsedItem> merged = new LinkedHashMap<>();
        for (ParsedItem item : ocrItems) {
            String key = normalizeMergeKey(item.name(), item.price());
            merged.put(key, item);
        }
        for (ParsedItem item : visionItems) {
            String key = normalizeMergeKey(item.name(), item.price());
            ParsedItem existing = merged.get(key);
            if (existing == null) {
                merged.put(key, item);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private String normalizeMergeKey(String name, BigDecimal price) {
        String normalizedName = normalizeProductName(name).replaceAll("\\s+", "");
        String normalizedPrice = safe(price).setScale(2, RoundingMode.HALF_UP).toPlainString();
        return normalizedName + "|" + normalizedPrice;
    }

    private BigDecimal chooseParsedTotal(
            BigDecimal ocrTotal,
            BigDecimal selectedTotal,
            BigDecimal visionTotal,
            List<ParsedItem> ocrItems,
            List<ParsedItem> visionItems,
            List<ParsedItem> selectedItems
    ) {
        BigDecimal ocr = safe(ocrTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal selected = safe(selectedTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vision = safe(visionTotal).setScale(2, RoundingMode.HALF_UP);

        if (selected.compareTo(BigDecimal.ZERO) > 0) {
            if (selectedItems == null || selectedItems.isEmpty()) {
                return selected;
            }
            if (selectedItems.size() >= 3) {
                return selected;
            }
        }
        if (!visionItems.isEmpty() && vision.compareTo(BigDecimal.ZERO) > 0) {
            return vision;
        }
        if (!ocrItems.isEmpty() && ocr.compareTo(BigDecimal.ZERO) > 0) {
            return ocr;
        }
        return selected.compareTo(BigDecimal.ZERO) > 0 ? selected : BigDecimal.ZERO;
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return trimmed.substring(start, end + 1);
    }

    private String normalizeCurrency(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("MXN") || "MX$".equals(upper) || "MEX".equals(upper)) {
            return "MXN";
        }
        if (upper.contains("USD") || "US$".equals(upper) || "$".equals(upper)) {
            return "USD";
        }
        if (upper.contains("EUR") || upper.contains("€")) {
            return "EUR";
        }
        return upper.length() > 3 ? upper.substring(0, 3) : upper;
    }

    private String mergeRawCorpus(String ocrText, String visionJson) {
        String left = ocrText == null ? "" : ocrText.trim();
        String right = visionJson == null ? "" : visionJson.trim();
        if (left.isBlank()) return right;
        if (right.isBlank()) return left;
        return left + "\n\n[VISION_JSON]\n" + right;
    }

    private String detectCurrency(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("USD") || upper.contains("US$")) {
            return "USD";
        }
        if (upper.contains("EUR") || upper.contains("€")) {
            return "EUR";
        }
        if (upper.contains("MXN") || upper.contains("MEX") || upper.contains("TOTAL $") || upper.contains("IMPORTE $")) {
            return "MXN";
        }
        return "USD";
    }

    private String detectStore(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        List<String> knownStores = List.of("WALMART", "COSTCO", "ALDI", "LIDL", "TARGET", "TESCO", "CARREFOUR", "HEB", "SORIANA", "BODEGA AURRERA", "MERCADONA");
        for (String knownStore : knownStores) {
            if (upper.contains(knownStore)) {
                return knownStore;
            }
        }

        return text.lines()
                .map(String::trim)
                .filter(line -> line.length() > 2)
                .filter(line -> !line.toLowerCase(Locale.ROOT).contains("image too small to scale"))
                .filter(line -> !line.toLowerCase(Locale.ROOT).contains("warning"))
                .filter(line -> !line.toLowerCase(Locale.ROOT).contains("dpi"))
                .findFirst()
                .orElse("Unknown Store");
    }

    private List<ParsedItem> parseItems(String text) {
        List<ParsedItem> items = new ArrayList<>();
        Map<String, ParsedItem> unique = new java.util.LinkedHashMap<>();

        for (String line : text.split("\\R")) {
            String trimmed = line.replace('\t', ' ').replaceAll("\\s{2,}", " ").trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() < 4 || SUMMARY_PATTERN.matcher(trimmed).matches() || isLikelyNonItemLine(trimmed)) {
                continue;
            }
            if (RECEIPT_CODE_LINE_PATTERN.matcher(trimmed).matches()) {
                continue;
            }
            if (RECEIPT_DISCOUNT_PATTERN.matcher(trimmed).matches() && trimmed.matches(".*[0-9][\\.,][0-9]{2}\\s*-\\s*$")) {
                continue;
            }

            ParsedItem strictParsed = parseStrictItemLine(trimmed);
            if (strictParsed != null) {
                upsertParsedItem(unique, strictParsed);
                continue;
            }

            Matcher moneyMatcher = MONEY_TOKEN_PATTERN.matcher(trimmed);
            int lastStart = -1;
            String lastToken = null;
            while (moneyMatcher.find()) {
                lastStart = moneyMatcher.start(1);
                lastToken = moneyMatcher.group(1);
            }
            if (lastToken == null || lastStart <= 0) {
                continue;
            }

            String name = trimmed.substring(0, lastStart).replaceAll("\\s{2,}", " ").trim();
            name = cleanupItemName(name);
            BigDecimal price = toMoney(lastToken);
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (name.length() < 2 || name.matches("[0-9\\-_/., ]+")) {
                continue;
            }

            BigDecimal confidence = scoreItemConfidence(trimmed, name, price);
            if (confidence.compareTo(BigDecimal.valueOf(0.45)) < 0) {
                continue;
            }

            upsertParsedItem(unique, new ParsedItem(name, price, confidence));
        }

        items.addAll(unique.values());
        return items;
    }

    private boolean isLikelyNonItemLine(String trimmed) {
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (RECEIPT_NOISE_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        if (lower.matches(".*\\b(calle|col\\.|cp\\b|regimen|rfc|telefono|tel\\.|mexico|quintana roo|playa del carmen)\\b.*")) {
            return true;
        }
        return lower.matches("^\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}.*$")
                || lower.matches("^\\d{1,2}:\\d{2}.*$")
                || lower.matches("^\\*{2,}.*")
                || lower.matches("^.{0,3}$");
    }

    private ParsedItem parseStrictItemLine(String line) {
        Matcher qtyTwoPrices = QTY_ITEM_TWO_PRICES_PATTERN.matcher(line);
        if (qtyTwoPrices.matches()) {
            String name = cleanupItemName(stripPriceFragments(qtyTwoPrices.group(1)));
            BigDecimal totalPrice = toMoney(qtyTwoPrices.group(3));
            if (name.length() >= 2 && totalPrice.compareTo(BigDecimal.ZERO) > 0) {
                return new ParsedItem(name, totalPrice, BigDecimal.valueOf(0.88).setScale(4, RoundingMode.HALF_UP));
            }
        }

        Matcher onePrice = ITEM_ONE_PRICE_PATTERN.matcher(line);
        if (onePrice.matches()) {
            String name = cleanupItemName(stripPriceFragments(onePrice.group(1)));
            BigDecimal price = toMoney(onePrice.group(2));
            if (name.length() >= 2 && price.compareTo(BigDecimal.ZERO) > 0 && !RECEIPT_DISCOUNT_PATTERN.matcher(line).matches()) {
                return new ParsedItem(name, price, BigDecimal.valueOf(0.82).setScale(4, RoundingMode.HALF_UP));
            }
        }
        return null;
    }

    private String stripPriceFragments(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("\\b[0-9]+[\\.,][0-9]{2}\\b", " ")
                .replaceAll("\\s+[A-Z]$", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private void upsertParsedItem(Map<String, ParsedItem> unique, ParsedItem candidate) {
        String currentKey = candidate.name().toLowerCase(Locale.ROOT) + "|" + candidate.price().toPlainString();
        ParsedItem existing = unique.get(currentKey);
        if (existing == null || safe(candidate.confidence()).compareTo(safe(existing.confidence())) > 0) {
            unique.put(currentKey, candidate);
        }
    }

    private BigDecimal scoreItemConfidence(String originalLine, String itemName, BigDecimal price) {
        double score = 0.45;
        String normalized = originalLine == null ? "" : originalLine.toLowerCase(Locale.ROOT);
        if (itemName.chars().filter(Character::isLetter).count() >= 3) score += 0.2;
        if (itemName.length() >= 4) score += 0.08;
        if (price.scale() >= 2) score += 0.1;
        if (QTY_PREFIX_PATTERN.matcher(originalLine).matches()) score += 0.1;
        if (BULK_PREFIX_PATTERN.matcher(originalLine).matches()) score += 0.05;
        if (normalized.matches(".*\\b(kg|g|gr|ml|lt|l|pcs|pc|pack)\\b.*")) score += 0.05;
        if (normalized.matches(".*\\b(total|subtotal|iva|tax|change|cash|visa|mastercard|payment|pago|discount|descuento)\\b.*")) score -= 0.35;
        return BigDecimal.valueOf(Math.max(0.0, Math.min(0.99, score))).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal parseTotal(String text, List<ParsedItem> items) {
        Pattern totalLinePattern = Pattern.compile(".*\\b(total|importe|sum|amount due|a pagar|grand total)\\b.*", Pattern.CASE_INSENSITIVE);
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!totalLinePattern.matcher(trimmed).matches()) {
                continue;
            }
            Matcher moneyMatcher = MONEY_TOKEN_PATTERN.matcher(trimmed);
            BigDecimal candidate = BigDecimal.ZERO;
            while (moneyMatcher.find()) {
                BigDecimal parsed = toMoney(moneyMatcher.group(1));
                if (parsed.compareTo(candidate) > 0) {
                    candidate = parsed;
                }
            }
            if (candidate.compareTo(BigDecimal.ZERO) > 0) {
                return candidate;
            }
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (ParsedItem item : items) {
            sum = sum.add(item.price());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal chooseMostPlausibleTotal(BigDecimal parsedTotal, BigDecimal itemSum, int itemCount) {
        BigDecimal total = safe(parsedTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sum = safe(itemSum).setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(BigDecimal.ZERO) <= 0) return sum;
        if (sum.compareTo(BigDecimal.ZERO) <= 0) return total;

        BigDecimal diff = total.subtract(sum).abs();
        BigDecimal tolerance = BigDecimal.valueOf(Math.max(2.0, total.doubleValue() * 0.06)).setScale(2, RoundingMode.HALF_UP);
        if (diff.compareTo(tolerance) <= 0) {
            return total;
        }

        // If line items are many and stable, prefer their sum over a likely OCR-misread total.
        if (itemCount >= 3) {
            return sum;
        }
        return total.compareTo(sum) >= 0 ? total : sum;
    }

    private BigDecimal toMoney(String raw) {
        try {
            String sanitized = raw.replaceAll("[^0-9,\\.]", "");
            if (sanitized.isBlank()) {
                return BigDecimal.ZERO;
            }

            int lastComma = sanitized.lastIndexOf(',');
            int lastDot = sanitized.lastIndexOf('.');
            if (lastComma >= 0 && lastDot >= 0) {
                if (lastComma > lastDot) {
                    sanitized = sanitized.replace(".", "").replace(',', '.');
                } else {
                    sanitized = sanitized.replace(",", "");
                }
            } else if (lastComma >= 0) {
                sanitized = hasTwoDecimals(sanitized, ',') ? sanitized.replace(',', '.') : sanitized.replace(",", "");
            } else if (lastDot >= 0) {
                sanitized = hasTwoDecimals(sanitized, '.') ? sanitized : sanitized.replace(".", "");
            }

            return new BigDecimal(sanitized).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private boolean hasTwoDecimals(String value, char separator) {
        int idx = value.lastIndexOf(separator);
        if (idx < 0 || idx + 3 != value.length()) {
            return false;
        }
        return Character.isDigit(value.charAt(value.length() - 1)) && Character.isDigit(value.charAt(value.length() - 2));
    }

    private String cleanupItemName(String rawName) {
        String cleaned = rawName
                .replaceAll("^[0-9]{4,}\\s+", "")
                .replaceAll("^\\d+\\s*[xX]\\s*", "")
                .replaceAll("[^\\p{L}0-9 .,'_\\-/]", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (cleaned.length() > 120) {
            return cleaned.substring(0, 120).trim();
        }
        return cleaned;
    }

    private String inferCategory(String text, List<ParsedItem> items, String storeName) {
        String corpus = ((text == null ? "" : text) + " " + (storeName == null ? "" : storeName)).toLowerCase(Locale.ROOT);
        long groceryHits = items.stream()
                .map(ParsedItem::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(name -> name.matches(".*(milk|bread|eggs|rice|pasta|cheese|fruit|vegetable|banana|apple|tomato|water|yogurt|coffee|meat|chicken|fish|soda|toilet paper|detergent|soap|shampoo).*"))
                .count();

        boolean supermarketStore = corpus.matches(".*\\b(walmart|costco|heb|soriana|aldi|lidl|target|carrefour|mercadona|supermarket|grocery)\\b.*");
        if (supermarketStore || groceryHits >= 2) {
            return "usual_weekly";
        }

        boolean travelHit = corpus.matches(".*\\b(hotel|airline|flight|uber|taxi|gas|fuel|airport|bus|train|hostel|booking)\\b.*");
        if (travelHit) {
            return "travel";
        }

        return "unusual_weekly";
    }

    private LocalDate startOfWeek(LocalDate date) {
        int delta = date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        return date.minusDays(delta);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal divide(BigDecimal value, long divisor) {
        if (divisor <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
    }

    private String normalizeProductName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.toLowerCase(Locale.ROOT)
                .replaceAll("\\b\\d+[xX]?\\b", " ")
                .replaceAll("\\b(kg|g|gr|ml|lt|l|pcs|pc|unidad|ud)\\b", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (cleaned.length() > 36) {
            return cleaned.substring(0, 36).trim();
        }
        return cleaned;
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "unusual_weekly";
        }
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        if ("travel".equals(normalized) || "usual_weekly".equals(normalized) || "unusual_weekly".equals(normalized)) {
            return normalized;
        }
        return "unusual_weekly";
    }

    private static class ProductAggregate {
        private long purchaseCount = 0;
        private BigDecimal totalPrice = BigDecimal.ZERO;
        private BigDecimal averageHistoricalPrice = BigDecimal.ZERO;
        private BigDecimal lastPrice = BigDecimal.ZERO;
        private LocalDate lastPurchaseDate = null;
        private Integer averageIntervalDays = null;
        private final List<LocalDate> purchaseDates = new ArrayList<>();
        private final List<BigDecimal> samplePrices = new ArrayList<>();

        private void addSample(BigDecimal price, LocalDate date) {
            purchaseCount += 1;
            totalPrice = totalPrice.add(price);
            samplePrices.add(price);
            purchaseDates.add(date);
            if (lastPurchaseDate == null || date.isAfter(lastPurchaseDate)) {
                lastPurchaseDate = date;
                lastPrice = price;
            }
            recalculateDerived();
        }

        private void recalculateDerived() {
            if (samplePrices.isEmpty()) {
                averageHistoricalPrice = BigDecimal.ZERO;
                return;
            }
            averageHistoricalPrice = samplePrices.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(samplePrices.size()), 2, RoundingMode.HALF_UP);

            if (purchaseDates.size() < 2) {
                averageIntervalDays = null;
                return;
            }
            List<LocalDate> sorted = purchaseDates.stream().sorted().toList();
            long totalDays = 0;
            int count = 0;
            for (int i = 1; i < sorted.size(); i++) {
                long diff = ChronoUnit.DAYS.between(sorted.get(i - 1), sorted.get(i));
                if (diff > 0) {
                    totalDays += diff;
                    count += 1;
                }
            }
            averageIntervalDays = count == 0 ? null : (int) Math.round((double) totalDays / count);
        }
    }

    private ShoppingReceiptVO toVOWithItems(ShoppingReceipt receipt) {
        List<ShoppingReceiptItemVO> items = itemRepository.findAllByReceiptOrderByPositionIndexAsc(receipt)
                .stream()
                .map(item -> ShoppingReceiptItemVO.builder()
                        .id(item.getId())
                        .positionIndex(item.getPositionIndex())
                        .name(item.getItemName())
                        .price(item.getItemPrice())
                        .confidence(item.getItemConfidence())
                        .build())
                .toList();
        return toVO(receipt, items);
    }

    private ShoppingReceiptVO toVO(ShoppingReceipt receipt, List<ShoppingReceiptItemVO> items) {
        return ShoppingReceiptVO.builder()
                .id(receipt.getId())
                .status(receipt.getStatus())
                .storeName(receipt.getStoreName())
                .currency(receipt.getCurrency())
                .totalAmount(receipt.getTotalAmount())
                .originalFileName(receipt.getOriginalFileName())
                .errorMessage(receipt.getErrorMessage())
                .savedAsExpense(Boolean.TRUE.equals(receipt.getSavedAsExpense()))
                .supermarketPurchase(Boolean.TRUE.equals(receipt.getIsSupermarketPurchase()))
                .inferredCategory(normalizeCategory(receipt.getInferredCategory()))
                .createdAt(receipt.getCreatedAt())
                .processedAt(receipt.getProcessedAt())
                .items(items)
                .build();
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record ParsedItem(String name, BigDecimal price, BigDecimal confidence) {
    }

    private record VisionImage(String mimeType, String base64) {
    }

    private record VisionExtraction(String storeName, String currency, BigDecimal total, List<ParsedItem> items, String rawText) {
        private static VisionExtraction empty() {
            return new VisionExtraction("", "", BigDecimal.ZERO, List.of(), "");
        }

        private boolean hasSignal() {
            return total.compareTo(BigDecimal.ZERO) > 0 || (items != null && !items.isEmpty()) || (rawText != null && !rawText.isBlank());
        }
    }
}
