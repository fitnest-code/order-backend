package az.fitnest.order.model.entity;

import az.fitnest.order.model.enums.HomeGymEndReason;
import az.fitnest.order.model.enums.HomeGymSource;
import az.fitnest.order.model.enums.HomeGymStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One row per home-gym selection / change / removal (spec data model).
 * Only one ACTIVE row may exist per subscription (partial unique index in V15).
 */
@Entity
@Table(name = "subscription_home_gym", indexes = {
        @Index(name = "idx_home_gym_subscription", columnList = "subscription_id"),
        @Index(name = "idx_home_gym_gym_status", columnList = "gym_id,status"),
        @Index(name = "idx_home_gym_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionHomeGym {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "gym_id", nullable = false)
    private Long gymId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private HomeGymStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private HomeGymSource source;

    @Column(name = "selected_at", nullable = false)
    private LocalDateTime selectedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 40)
    private HomeGymEndReason endReason;

    /** Planned end at the subscriber's current billing-period end (R18). */
    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "end_reason_visit_id")
    private Long endReasonVisitId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
