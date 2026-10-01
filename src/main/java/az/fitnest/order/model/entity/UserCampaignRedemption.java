package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_campaign_redemptions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "campaign_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserCampaignRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "payment_order_id", length = 128)
    private String paymentOrderId;

    @Column(name = "redeemed_at", nullable = false)
    private LocalDateTime redeemedAt;

    @PrePersist
    protected void onCreate() {
        if (redeemedAt == null) redeemedAt = LocalDateTime.now();
    }
}
