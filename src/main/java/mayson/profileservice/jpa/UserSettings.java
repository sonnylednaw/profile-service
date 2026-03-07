package mayson.profileservice.jpa;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "user_settings", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    @Column(name = "email", length = 160)
    private String email;

    @Column(name = "goal_per_week", precision = 12, scale = 2)
    private BigDecimal goalPerWeek;

    @Column(name = "goal_per_month", precision = 12, scale = 2)
    private BigDecimal goalPerMonth;

    @Column(name = "goal_currency", length = 3)
    private String goalCurrency;

    @Column(name = "total_salary", precision = 12, scale = 2)
    private BigDecimal totalSalary;

    @Column(name = "salary_currency", length = 3)
    private String salaryCurrency;

    @Column(name = "fixed_costs", precision = 12, scale = 2)
    private BigDecimal fixedCosts;

    @Column(name = "timezone", length = 100)
    private String timezone;

    @Column(name = "preferred_currency", length = 3)
    private String preferredCurrency;
}
