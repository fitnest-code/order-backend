package az.fitnest.order.model.entity;

import az.fitnest.order.model.enums.ConfirmationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Single-use confirmation token for switching home gym -> standard mode (R5).
 * Bound to user + subscription + target gym and expires after 5 minutes.
 */
@Entity
@Table(name = "home_gym_confirmation_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HomeGymConfirmationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "target_gym_id", nullable = false)
    private Long targetGymId;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_type", nullable = false, length = 40)
    private ConfirmationType confirmationType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
