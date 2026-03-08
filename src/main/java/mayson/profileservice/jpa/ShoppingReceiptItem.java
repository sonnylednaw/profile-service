package mayson.profileservice.jpa;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "shopping_receipt_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingReceiptItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private ShoppingReceipt receipt;

    @Column(name = "position_idx", nullable = false)
    private Integer positionIndex;

    @Column(name = "item_name", nullable = false, length = 220)
    private String itemName;

    @Column(name = "item_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemPrice;
}
