package mayson.profileservice.vo;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingCategoryStatVO {
    private String category;
    private Long receipts;
}
