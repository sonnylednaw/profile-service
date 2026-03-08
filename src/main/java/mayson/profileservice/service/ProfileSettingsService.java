package mayson.profileservice.service;

import mayson.profileservice.jpa.RecurringCost;
import mayson.profileservice.jpa.UserSettings;
import mayson.profileservice.repository.RecurringCostRepository;
import mayson.profileservice.repository.UserSettingsRepository;
import mayson.profileservice.vo.ProfileVO;
import mayson.profileservice.vo.RecurringCostVO;
import mayson.profileservice.vo.SettingsVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProfileSettingsService {

    private final UserSettingsRepository repository;
    private final RecurringCostRepository recurringCostRepository;

    public ProfileSettingsService(
            UserSettingsRepository repository,
            RecurringCostRepository recurringCostRepository
    ) {
        this.repository = repository;
        this.recurringCostRepository = recurringCostRepository;
    }

    private UserSettings loadOrCreate(String userId) {
        return repository.findByUserId(userId)
                .orElseGet(() -> repository.save(UserSettings.builder()
                        .userId(userId)
                        .goalPerWeek(BigDecimal.ZERO)
                        .goalPerMonth(BigDecimal.ZERO)
                        .goalCurrency("MXN")
                        .totalSalary(BigDecimal.ZERO)
                        .salaryCurrency("MXN")
                        .carryOverToNextWeek(false)
                        .fixedCosts(BigDecimal.ZERO)
                        .timezone("America/Cancun")
                        .preferredCurrency("MXN")
                        .build()));
    }

    public ProfileVO getProfile(String userId) {
        UserSettings settings = loadOrCreate(userId);
        return ProfileVO.builder()
                .firstName(settings.getFirstName())
                .lastName(settings.getLastName())
                .email(settings.getEmail())
                .build();
    }

    public ProfileVO updateProfile(String userId, ProfileVO profileVO) {
        UserSettings settings = loadOrCreate(userId);
        settings.setFirstName(profileVO.getFirstName());
        settings.setLastName(profileVO.getLastName());
        settings.setEmail(profileVO.getEmail());
        UserSettings saved = repository.save(settings);
        return ProfileVO.builder()
                .firstName(saved.getFirstName())
                .lastName(saved.getLastName())
                .email(saved.getEmail())
                .build();
    }

    public SettingsVO getSettings(String userId) {
        UserSettings settings = loadOrCreate(userId);
        return SettingsVO.builder()
                .goalPerWeek(settings.getGoalPerWeek())
                .goalPerMonth(settings.getGoalPerMonth())
                .goalCurrency(normalizeCurrency(settings.getGoalCurrency()))
                .totalSalary(settings.getTotalSalary())
                .salaryCurrency(normalizeCurrency(settings.getSalaryCurrency()))
                .carryOverToNextWeek(Boolean.TRUE.equals(settings.getCarryOverToNextWeek()))
                .fixedCosts(settings.getFixedCosts())
                .timezone(settings.getTimezone())
                .preferredCurrency(settings.getPreferredCurrency())
                .build();
    }

    public SettingsVO updateSettings(String userId, SettingsVO settingsVO) {
        UserSettings settings = loadOrCreate(userId);
        settings.setGoalPerWeek(safe(settingsVO.getGoalPerWeek()));
        settings.setGoalPerMonth(safe(settingsVO.getGoalPerMonth()));
        settings.setGoalCurrency(normalizeCurrency(settingsVO.getGoalCurrency()));
        settings.setTotalSalary(safe(settingsVO.getTotalSalary()));
        settings.setSalaryCurrency(normalizeCurrency(settingsVO.getSalaryCurrency()));
        settings.setCarryOverToNextWeek(Boolean.TRUE.equals(settingsVO.getCarryOverToNextWeek()));
        settings.setFixedCosts(safe(settingsVO.getFixedCosts()));
        settings.setTimezone(settingsVO.getTimezone() == null || settingsVO.getTimezone().isBlank() ? "America/Cancun" : settingsVO.getTimezone());
        settings.setPreferredCurrency(settingsVO.getPreferredCurrency() == null || settingsVO.getPreferredCurrency().isBlank() ? "MXN" : settingsVO.getPreferredCurrency().toUpperCase());
        UserSettings saved = repository.save(settings);
        return SettingsVO.builder()
                .goalPerWeek(saved.getGoalPerWeek())
                .goalPerMonth(saved.getGoalPerMonth())
                .goalCurrency(normalizeCurrency(saved.getGoalCurrency()))
                .totalSalary(saved.getTotalSalary())
                .salaryCurrency(normalizeCurrency(saved.getSalaryCurrency()))
                .carryOverToNextWeek(Boolean.TRUE.equals(saved.getCarryOverToNextWeek()))
                .fixedCosts(saved.getFixedCosts())
                .timezone(saved.getTimezone())
                .preferredCurrency(saved.getPreferredCurrency())
                .build();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public List<RecurringCostVO> getRecurringCosts(String userId) {
        return recurringCostRepository.findAllByUserIdOrderByIdAsc(userId)
                .stream()
                .map(cost -> RecurringCostVO.builder()
                        .id(cost.getId())
                        .label(cost.getLabel())
                        .category(cost.getCategory())
                        .paymentPeriod(cost.getPaymentPeriod())
                        .currency(normalizeCurrency(cost.getCurrency()))
                        .amount(cost.getAmount())
                        .build())
                .toList();
    }

    public List<RecurringCostVO> replaceRecurringCosts(String userId, List<RecurringCostVO> costs) {
        recurringCostRepository.deleteAllByUserId(userId);
        List<RecurringCost> entities = costs.stream()
                .filter(cost -> cost.getLabel() != null && !cost.getLabel().isBlank())
                .map(cost -> RecurringCost.builder()
                        .userId(userId)
                        .label(cost.getLabel().trim())
                        .category(cost.getCategory() == null || cost.getCategory().isBlank() ? "subscription" : cost.getCategory().trim())
                        .paymentPeriod(normalizePaymentPeriod(cost.getPaymentPeriod()))
                        .currency(normalizeCurrency(cost.getCurrency()))
                        .amount(safe(cost.getAmount()))
                        .build())
                .toList();
        List<RecurringCost> saved = recurringCostRepository.saveAll(entities);
        return saved.stream()
                .map(cost -> RecurringCostVO.builder()
                        .id(cost.getId())
                        .label(cost.getLabel())
                        .category(cost.getCategory())
                        .paymentPeriod(cost.getPaymentPeriod())
                        .currency(normalizeCurrency(cost.getCurrency()))
                        .amount(cost.getAmount())
                        .build())
                .toList();
    }

    private String normalizePaymentPeriod(String paymentPeriod) {
        if (paymentPeriod == null) {
            return "MONTHLY";
        }
        String normalized = paymentPeriod.trim().toUpperCase();
        if (normalized.equals("WEEKLY") || normalized.equals("YEARLY") || normalized.equals("MONTHLY")) {
            return normalized;
        }
        return "MONTHLY";
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "MXN";
        }
        String normalized = currency.trim().toUpperCase();
        if (normalized.length() == 3) {
            return normalized;
        }
        return "MXN";
    }
}
