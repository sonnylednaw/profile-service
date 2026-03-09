package mayson.profileservice.vo;

import lombok.*;

import java.math.BigDecimal;

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
}
