package az.fitnest.order.model.entity;

import az.fitnest.order.model.enums.SettlementMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Monthly settlement line per (gym, subscription, subscription-month), R16/R17.
 */
@Entity
@Table(name = "gym_settlement_line")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GymSettlementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Calendar month in which the job ran, e.g. {@code 2026-11}. */
    @Column(name = "run_month", nullable = false, length = 7)
    private String runMonth;

    @Column(name = "gym_id", nullable = false)
    private Long gymId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "visits", nullable = false)
    private Integer visits;

    @Column(name = "home_visits", nullable = false)
    private Integer homeVisits;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private SettlementMode mode;

    @Column(name = "price_package", length = 30)
    private String pricePackage;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breakdown", columnDefinition = "jsonb")
    private Map<String, Object> breakdown;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
