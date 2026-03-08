package mayson.profileservice.vo;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettingsVO {
    private BigDecimal goalPerWeek;
    private BigDecimal goalPerMonth;
    private String goalCurrency;
    private BigDecimal totalSalary;
    private String salaryCurrency;
    private Boolean carryOverToNextWeek;
    private Boolean shoppingBetaEnabled;
    private BigDecimal fixedCosts;
    private String timezone;
    private String preferredCurrency;
}
