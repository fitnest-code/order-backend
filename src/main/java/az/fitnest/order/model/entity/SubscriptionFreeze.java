package az.fitnest.order.model.entity;

import az.fitnest.order.model.enums.FreezeStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscription_freezes", indexes = {
        @Index(name = "idx_freeze_sub_id", columnList = "subscription_id"),
        @Index(name = "idx_freeze_user_id", columnList = "user_id"),
        @Index(name = "idx_freeze_status", columnList = "status"),
        @Index(name = "idx_freeze_plan_end_at", columnList = "plan_end_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionFreeze {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "requested_days", nullable = false)
    private int requestedDays;

    @Column(name = "used_days")
    private Integer usedDays;

    @Column(name = "returned_days")
    private Integer returnedDays;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "plan_end_at", nullable = false)
    private LocalDateTime planEndAt;

    @Column(name = "actual_end_at")
    private LocalDateTime actualEndAt;

    @Column(name = "expiry_before", nullable = false)
    private LocalDateTime expiryBefore;

    @Column(name = "expiry_after")
    private LocalDateTime expiryAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FreezeStatus status;

    @Column(name = "ended_by", length = 30)
    private String endedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return FreezeStatus.ACTIVE == status;
    }

    public boolean isTerminal() {
        return status == FreezeStatus.COMPLETED
                || status == FreezeStatus.ENDED_EARLY
                || status == FreezeStatus.TERMINATED;
    }

    public boolean isPlanEndReached() {
        return planEndAt != null && !LocalDateTime.now().isBefore(planEndAt);
    }
}
