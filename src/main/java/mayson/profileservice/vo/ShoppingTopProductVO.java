package mayson.profileservice.vo;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingTopProductVO {
    private String name;
    private Long purchaseCount;
    private BigDecimal avgUnitsPerWeek;
    private BigDecimal avgSpendPerWeek;
    private BigDecimal avgPrice;
    private BigDecimal lastPrice;
    private BigDecimal priceTrendPct;
    private Integer estimatedNextPurchaseDays;
    private LocalDate lastPurchasedAt;
}
