package mayson.profileservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import mayson.profileservice.service.ProfileSettingsService;
import mayson.profileservice.vo.ProfileVO;
import mayson.profileservice.vo.RecurringCostVO;
import mayson.profileservice.vo.SettingsVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping
@Tag(name = "Profile Settings")
@SecurityRequirement(name = "oauth2")
public class ProfileController {

    private final ProfileSettingsService service;

    public ProfileController(ProfileSettingsService service) {
        this.service = service;
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
}
