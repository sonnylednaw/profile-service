package mayson.profileservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import mayson.profileservice.service.ProfileSettingsService;
import mayson.profileservice.service.ShoppingReceiptService;
import mayson.profileservice.service.AssistantService;
import mayson.profileservice.vo.AssistantAskRequestVO;
import mayson.profileservice.vo.AssistantAskResponseVO;
import mayson.profileservice.vo.ProfileVO;
import mayson.profileservice.vo.RecurringCostVO;
import mayson.profileservice.vo.SettingsVO;
import mayson.profileservice.vo.ShoppingAnalyticsVO;
import mayson.profileservice.vo.ShoppingReceiptVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
@Tag(name = "Profile Settings")
@SecurityRequirement(name = "oauth2")
public class ProfileController {

    private final ProfileSettingsService service;
    private final ShoppingReceiptService shoppingReceiptService;
    private final AssistantService assistantService;

    public ProfileController(
            ProfileSettingsService service,
            ShoppingReceiptService shoppingReceiptService,
            AssistantService assistantService
    ) {
        this.service = service;
        this.shoppingReceiptService = shoppingReceiptService;
        this.assistantService = assistantService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('SCOPE_finances.read')")
    @Operation(summary = "Get current user profile")
    public ProfileVO getProfile(Authentication authentication) {
        return service.getProfile(authentication.getName());
    }

    @PutMapping("/me")
    @PreAuthorize("hasAuthority('SCOPE_finances.write')")
    @Operation(summary = "Update current user profile")
    public ProfileVO updateProfile(Authentication authentication, @RequestBody ProfileVO profileVO) {
        return service.updateProfile(authentication.getName(), profileVO);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('SCOPE_finances.read')")
    @Operation(summary = "Get user dashboard/settings preferences")
    public SettingsVO getSettings(Authentication authentication) {
        return service.getSettings(authentication.getName());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SCOPE_finances.write')")
    @Operation(summary = "Update user dashboard/settings preferences")
    public SettingsVO updateSettings(Authentication authentication, @RequestBody SettingsVO settingsVO) {
        return service.updateSettings(authentication.getName(), settingsVO);
    }

    @GetMapping("/costs")
    @PreAuthorize("hasAuthority('SCOPE_finances.read')")
    @Operation(summary = "Get recurring fixed costs and subscriptions")
    public List<RecurringCostVO> getRecurringCosts(Authentication authentication) {
        return service.getRecurringCosts(authentication.getName());
    }

    @PutMapping("/costs")
    @PreAuthorize("hasAuthority('SCOPE_finances.write')")
    @Operation(summary = "Replace recurring fixed costs and subscriptions")
    public List<RecurringCostVO> replaceRecurringCosts(
            Authentication authentication,
            @RequestBody List<RecurringCostVO> costs
    ) {
        return service.replaceRecurringCosts(authentication.getName(), costs);
    }

    @PostMapping(value = "/shopping/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_finances.write')")
    @Operation(summary = "Upload shopping receipt image/PDF and start async OCR extraction")
    public ShoppingReceiptVO uploadShoppingReceipt(
            Authentication authentication,
            @RequestPart("image") MultipartFile image
    ) {
        return shoppingReceiptService.uploadReceipt(authentication.getName(), image);
    }

    @GetMapping("/shopping/receipts")
    @PreAuthorize("hasAuthority('SCOPE_finances.read')")
    @Operation(summary = "List uploaded shopping receipts")
    public List<ShoppingReceiptVO> listShoppingReceipts(Authentication authentication) {
        return shoppingReceiptService.listReceipts(authentication.getName());
    }

    @GetMapping("/shopping/receipts/{receiptId}")
    @PreAuthorize("hasAuthority('SCOPE_finances.read')")
    @Operation(summary = "Get shopping receipt details")
    public ShoppingReceiptVO getShoppingReceipt(
            Authentication authentication,
            @PathVariable Long receiptId
    ) {
        return shoppingReceiptService.getReceipt(authentication.getName(), receiptId);
    }

    @DeleteMapping("/shopping/receipts/{receiptId}")
    @PreAuthorize("hasAuthority('SCOPE_finances.write')")
    @Operation(summary = "Delete shopping receipt and extracted items")
    public void deleteShoppingReceipt(
            Authentication authentication,
            @PathVariable Long receiptId
    ) {
        shoppingReceiptService.deleteReceipt(authentication.getName(), receiptId);
    }

    @PostMapping("/shopping/receipts/{receiptId}/mark-expense-saved")
    @PreAuthorize("hasAuthority('SCOPE_finances.write')")
    @Operation(summary = "Mark shopping receipt as saved to expenses")
    public ShoppingReceiptVO markReceiptSavedAsExpense(
            Authentication authentication,
            @PathVariable Long receiptId
    ) {
        return shoppingReceiptService.markSavedAsExpense(authentication.getName(), receiptId);
    }

    @PutMapping("/shopping/receipts/{receiptId}/classification")
    @PreAuthorize("hasAuthority('SCOPE_finances.write')")
    @Operation(summary = "Update supermarket marker/classification for a receipt")
    public ShoppingReceiptVO updateReceiptClassification(
            Authentication authentication,
            @PathVariable Long receiptId,
            @RequestBody Map<String, Boolean> payload
    ) {
        boolean supermarketPurchase = Boolean.TRUE.equals(payload.get("supermarketPurchase"));
        return shoppingReceiptService.updateSupermarketClassification(authentication.getName(), receiptId, supermarketPurchase);
    }

    @GetMapping("/shopping/analytics")
    @PreAuthorize("hasAuthority('SCOPE_finances.read')")
    @Operation(summary = "Get shopping analytics and weekly estimations")
    public ShoppingAnalyticsVO getShoppingAnalytics(Authentication authentication) {
        return shoppingReceiptService.getAnalytics(authentication.getName());
    }

    @PostMapping("/assistant/ask")
    @PreAuthorize("hasAuthority('SCOPE_finances.read')")
    @Operation(summary = "Ask the finance AI assistant with grounded user data context")
    public AssistantAskResponseVO askAssistant(
            Authentication authentication,
            @RequestBody AssistantAskRequestVO request
    ) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException("Question is required.");
        }
        return assistantService.ask(authentication.getName(), request);
    }
}
