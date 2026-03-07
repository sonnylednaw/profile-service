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
    private BigDecimal totalSalary;
    private BigDecimal fixedCosts;
    private String timezone;
    private String preferredCurrency;
}
