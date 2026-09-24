package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

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

    public void finaliseConsumed(int usedDays, int returnedDays) {
        this.reservedDays -= (usedDays + returnedDays);
        this.consumedDays += usedDays;
    }
}
