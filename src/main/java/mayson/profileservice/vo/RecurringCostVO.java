package mayson.profileservice.vo;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringCostVO {
    private Long id;
    private String label;
    private String category;
    private String paymentPeriod;
    private String currency;
    private BigDecimal amount;
}
