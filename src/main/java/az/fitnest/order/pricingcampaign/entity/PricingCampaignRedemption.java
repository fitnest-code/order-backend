package az.fitnest.order.pricingcampaign.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Written on successful payment; never deleted on refund (spec: refund does not clear redemption). */
@Entity
@Table(name = "pricing_campaign_redemptions")
@Getter
@Setter
@NoArgsConstructor
public class PricingCampaignRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "redeemed_at", nullable = false)
    private OffsetDateTime redeemedAt;
}
