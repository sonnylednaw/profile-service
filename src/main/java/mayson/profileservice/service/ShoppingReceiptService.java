package mayson.profileservice.service;

import mayson.profileservice.jpa.ShoppingReceipt;
import mayson.profileservice.jpa.ShoppingReceiptItem;
import mayson.profileservice.repository.ShoppingReceiptItemRepository;
import mayson.profileservice.repository.ShoppingReceiptRepository;
import mayson.profileservice.vo.ShoppingReceiptItemVO;
import mayson.profileservice.vo.ShoppingReceiptVO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

@Service
public class ShoppingReceiptService {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    private final ShoppingReceiptRepository receiptRepository;
    private final ShoppingReceiptItemRepository itemRepository;

    public ShoppingReceiptService(
            ShoppingReceiptRepository receiptRepository,
            ShoppingReceiptItemRepository itemRepository
    ) {
        this.receiptRepository = receiptRepository;
        this.itemRepository = itemRepository;
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
    public ShoppingReceiptVO markSavedAsExpense(String userId, Long receiptId) {
        ShoppingReceipt receipt = receiptRepository.findByIdAndUserId(receiptId, userId)
                .orElseThrow(() -> new RuntimeException("Receipt not found"));
        receipt.setSavedAsExpense(true);
        return toVOWithItems(receiptRepository.save(receipt));
    }

    public void processReceiptAsync(Long receiptId, String userId, String originalFileName, byte[] content) {
        ShoppingReceipt receipt = receiptRepository.findByIdAndUserId(receiptId, userId)
                .orElseThrow(() -> new RuntimeException("Receipt not found"));

        try {
            String text = extractText(content, originalFileName);
            List<ParsedItem> parsedItems = parseItems(text);
            BigDecimal total = parseTotal(text, parsedItems);
            String currency = detectCurrency(text);
            String storeName = detectStore(text);

            receipt.setStatus(STATUS_COMPLETED);
            receipt.setStoreName(trim(storeName, 180));
            receipt.setCurrency(currency);
            receipt.setTotalAmount(total);
            receipt.setRawText(trim(text, 40000));
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

    private String extractText(byte[] content, String originalFileName) throws Exception {
        if (isPdf(originalFileName)) {
            return extractTextFromPdf(content);
        }
        return extractTextFromImage(content);
    }

    private String extractTextFromPdf(byte[] content) throws Exception {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String directText = stripper.getText(document);
            if (directText != null && directText.replaceAll("\\s+", "").length() > 20) {
                return directText;
            }

            PDFRenderer renderer = new PDFRenderer(document);
            StringBuilder builder = new StringBuilder();
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 220f, ImageType.RGB);
                byte[] pngBytes = toPng(image);
                builder.append(extractTextFromImage(pngBytes)).append("\n");
            }
            return builder.toString();
        }
    }

    private byte[] toPng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }

    private String extractTextFromImage(byte[] imageBytes) throws Exception {
        Path tempImage = Files.createTempFile("mayson-receipt-", ".img");
        Files.write(tempImage, imageBytes);

        Process process = new ProcessBuilder("tesseract", tempImage.toString(), "stdout", "-l", "eng+spa")
                .redirectErrorStream(true)
                .start();

        String output;
        try (InputStream inputStream = process.getInputStream()) {
            output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        int exitCode = process.waitFor();
        Files.deleteIfExists(tempImage);

        if (exitCode != 0) {
            throw new RuntimeException("OCR failed. Please upload a clearer image/PDF.");
        }

        return output == null ? "" : output;
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
                .findFirst()
                .orElse("Unknown Store");
    }

    private List<ParsedItem> parseItems(String text) {
        Pattern pattern = Pattern.compile("^([\\p{L}0-9 .,'_\\-/]{3,}?)\\s+([0-9]+(?:[\\.,][0-9]{2}))$");
        List<ParsedItem> items = new ArrayList<>();

        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher matcher = pattern.matcher(trimmed);
            if (!matcher.matches()) {
                continue;
            }

            String name = matcher.group(1).trim();
            BigDecimal price = toMoney(matcher.group(2));
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (name.toLowerCase(Locale.ROOT).matches(".*(total|subtotal|tax|iva|change|cash|visa|mastercard).*")) {
                continue;
            }

            items.add(new ParsedItem(name, price));
        }

        return items;
    }

    private BigDecimal parseTotal(String text, List<ParsedItem> items) {
        Pattern totalPattern = Pattern.compile("(?:total|importe|sum)\\D*([0-9]+(?:[\\.,][0-9]{2}))", Pattern.CASE_INSENSITIVE);
        for (String line : text.split("\\R")) {
            Matcher matcher = totalPattern.matcher(line.trim());
            if (matcher.find()) {
                BigDecimal value = toMoney(matcher.group(1));
                if (value.compareTo(BigDecimal.ZERO) > 0) {
                    return value;
                }
            }
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (ParsedItem item : items) {
            sum = sum.add(item.price());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal toMoney(String raw) {
        try {
            return new BigDecimal(raw.replace(',', '.')).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
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

    private record ParsedItem(String name, BigDecimal price) {
    }
}
