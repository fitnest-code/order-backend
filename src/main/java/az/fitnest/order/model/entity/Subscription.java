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

    /**
     * @deprecated BRD v1.1: frozenAt is now tracked on SubscriptionFreeze.startAt.
     * Kept for backward sync with mobile until cutover. Do not write new business logic against this.
     */
    @Deprecated(since = "BRD-v1.1", forRemoval = false)
    @Column(name = "frozen_at")
    private LocalDateTime frozenAt;

    /**
     * @deprecated BRD v1.1: consumed days tracked on SubscriptionFreezeEntitlement.consumedDays.
     * Written for backward sync only.
     */
    @Deprecated(since = "BRD-v1.1", forRemoval = false)
    @Column(name = "frozen_days_used")
    private Integer frozenDaysUsed = 0;

    /**
     * @deprecated BRD v1.1: entitlement now sourced from SubscriptionFreezeEntitlement.totalDays.
     * Hardcoded to 0 in legacy code — do not use as source of truth.
     */
    @Deprecated(since = "BRD-v1.1", forRemoval = false)
    @Column(name = "allowed_freeze_days")
    private Integer allowedFreezeDays;

    /**
     * @deprecated BRD v1.1: unfreeze time tracked on SubscriptionFreeze.planEndAt.
     * Kept for backward sync with mobile until cutover.
     */
    @Deprecated(since = "BRD-v1.1", forRemoval = false)
    @Column(name = "unfreezes_at")
    private LocalDateTime unfreezesAt;

    @Column(name = "is_upgraded")
    private Boolean isUpgraded = false;

    @Column(name = "auto_payment_enabled")
    private Boolean autoPaymentEnabled = false;

    @Column(name = "frozen_sessions")
    private Integer frozenSessions = 0;

    @Version
    @Column(name = "version")
    private Long version = 0L;

    public Long getVersion() {
        return version != null ? version : 0L;
    }
}
