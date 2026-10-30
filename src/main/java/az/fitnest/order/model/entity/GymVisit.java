package az.fitnest.order.model.entity;

import az.fitnest.order.model.enums.VisitEntryType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "gym_visits", indexes = {
        @Index(name = "idx_gym_visits_sub_period", columnList = "subscription_id,period_start"),
        @Index(name = "idx_gym_visits_user_gym_period", columnList = "user_id,gym_id,period_start")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GymVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "gym_id", nullable = false)
    private Long gymId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "checked_in_at", nullable = false)
    private LocalDateTime checkedInAt;

    /** HOME_UNLIMITED = home-gym visit (R3); STANDARD = counted against the monthly limit. */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 30)
    private VisitEntryType entryType;

    /** Home gym that was active at check-in time, snapshot for settlement (R16). */
    @Column(name = "home_gym_id")
    private Long homeGymId;

    /** Subscription-month start date (e.g. 05.10 for 05.10–04.11). */
    @Column(name = "period_start")
    private LocalDate periodStart;

    /** Catalog time-slot rule applied at check-in, null = default gym hours (R14). */
    @Column(name = "time_slot_rule_id")
    private Long timeSlotRuleId;

    /** Package whose price was used (R15); not necessarily the subscriber's package. */
    @Column(name = "price_package", length = 30)
    private String pricePackage;

    @Column(name = "single_price", precision = 10, scale = 2)
    private BigDecimal singlePrice;

    @Column(name = "unlimited_price", precision = 10, scale = 2)
    private BigDecimal unlimitedPrice;

    /** False for frozen/reservation visits that must not consume the monthly limit. */
    @Builder.Default
    @Column(name = "counts_towards_limit", nullable = false)
    private Boolean countsTowardsLimit = true;

    /** T-16: client-generated scan id, used to collapse retried QR scans into one visit. */
    @Column(name = "qr_scan_id", length = 120)
    private String qrScanId;
}
