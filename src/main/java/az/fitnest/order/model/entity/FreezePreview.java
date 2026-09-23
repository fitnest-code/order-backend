package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "freeze_previews", indexes = {
        @Index(name = "idx_preview_sub_user", columnList = "subscription_id, user_id"),
        @Index(name = "idx_preview_expires", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezePreview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "requested_days", nullable = false)
    private int requestedDays;

    @Column(name = "plan_end_at", nullable = false)
    private LocalDateTime planEndAt;

    @Column(name = "expiry_after", nullable = false)
    private LocalDateTime expiryAfter;

    @Column(name = "expected_version", nullable = false)
    private long expectedVersion;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
