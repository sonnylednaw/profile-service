package mayson.profileservice.jpa;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shopping_receipt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "store_name", length = 180)
    private String storeName;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "original_file_name", length = 255)
    private String originalFileName;

    @Column(name = "raw_text", columnDefinition = "text")
    private String rawText;

    @Column(name = "error_message", length = 600)
    private String errorMessage;

    @Column(name = "saved_as_expense", nullable = false)
    private Boolean savedAsExpense;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
