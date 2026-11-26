package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.YearMonth;

@Entity
@Table(name = "subscription_freeze_entitlements", indexes = {
        @Index(name = "idx_entitlement_sub_id", columnList = "subscription_id"),
        @Index(name = "idx_entitlement_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionFreezeEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false, unique = true)
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_days", nullable = false)
    private int totalDays;

    @Column(name = "consumed_days", nullable = false)
    private int consumedDays;

    @Column(name = "reserved_days", nullable = false)
    private int reservedDays;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(name = "allowance_month", length = 7)
    private String allowanceMonth;

    // Field access: Hibernate increments this directly — never mask null in a getter.
    @Version
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PostLoad
    void normalizeVersion() {
        if (version == null) {
            version = 0L;
        }
    }

    @PrePersist
    protected void onCreate() {
        if (version == null) {
            version = 0L;
        }
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public int getAvailableDays() {
        return Math.max(0, totalDays - consumedDays - reservedDays);
    }

    public boolean canFreeze(int days) {
        return days >= 1 && days <= getAvailableDays();
    }

    public void reserve(int days) {
        if (!canFreeze(days)) {
            throw new IllegalStateException(
                    "Cannot reserve %d days; available=%d".formatted(days, getAvailableDays()));
        }
        this.reservedDays += days;
    }

    /**
     * A freeze started in a previous allowance month must not charge the current
     * month's budget; its reserved days are still released when it ends.
     */
    public void finaliseConsumed(int usedDays, int returnedDays, boolean chargeCurrentMonth) {
        if (usedDays < 0 || returnedDays < 0) {
            throw new IllegalArgumentException("usedDays and returnedDays must be non-negative");
        }
        this.reservedDays = Math.max(0, this.reservedDays - usedDays - returnedDays);
        if (chargeCurrentMonth) {
            this.consumedDays += usedDays;
        }
    }

    public boolean belongsToAllowanceMonth(LocalDateTime freezeStartAt) {
        if (allowanceMonth == null || freezeStartAt == null) {
            return true; // legacy row — preserve previous behaviour
        }
        return allowanceMonth.equals(YearMonth.from(freezeStartAt).toString());
    }
}
