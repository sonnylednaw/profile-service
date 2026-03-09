package mayson.profileservice.vo;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingAnalyticsVO {
    private BigDecimal averageProductsPerWeek;
    private BigDecimal averageSpendPerWeek;
    private BigDecimal currentWeekProducts;
    private BigDecimal currentWeekSpend;
    private Long currentWeekReceipts;
    private List<ShoppingTopProductVO> topProducts;
    private List<ShoppingCategoryStatVO> categoryBreakdown;
}
