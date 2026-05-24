package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions", indexes = {
    @Index(name = "idx_subscription_user_id", columnList = "user_id"),
    @Index(name = "idx_subscription_plan_id", columnList = "plan_id"),
    @Index(name = "idx_subscription_option_id", columnList = "option_id"),
    @Index(name = "idx_subscription_status", columnList = "status"),
    @Index(name = "idx_subscription_end_at", columnList = "end_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_id", nullable = false)
    private Long packageId;

    @Column(name = "option_id")
    private Long optionId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Column(name = "total_limit")
    private Integer totalLimit;

    @Column(name = "remaining_limit")
    private Integer remainingLimit;

    @Column(name = "frozen_at")
    private LocalDateTime frozenAt;

    @Column(name = "frozen_days_used")
    private Integer frozenDaysUsed = 0;

    @Column(name = "allowed_freeze_days")
    private Integer allowedFreezeDays;

    @Column(name = "unfreezes_at")
    private LocalDateTime unfreezesAt;

    @Column(name = "is_upgraded")
    private Boolean isUpgraded = false;

    @Column(name = "auto_payment_enabled")
    private Boolean autoPaymentEnabled = false;

    @Column(name = "frozen_sessions")
    private Integer frozenSessions = 0;
}
