package mayson.profileservice.jpa;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "recurring_cost")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringCost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Column(name = "payment_period", nullable = false, length = 20)
    private String paymentPeriod;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
}
