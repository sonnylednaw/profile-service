package mayson.profileservice.vo;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingReceiptItemVO {
    private Long id;
    private Integer positionIndex;
    private String name;
    private BigDecimal price;
    private BigDecimal confidence;
}
