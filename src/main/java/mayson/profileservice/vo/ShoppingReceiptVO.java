package mayson.profileservice.vo;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingReceiptVO {
    private Long id;
    private String status;
    private String storeName;
    private String currency;
    private BigDecimal totalAmount;
    private String originalFileName;
    private String errorMessage;
    private Boolean savedAsExpense;
    private Boolean supermarketPurchase;
    private String inferredCategory;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
    private List<ShoppingReceiptItemVO> items;
}
