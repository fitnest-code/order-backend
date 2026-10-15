package az.fitnest.order.model.entity.promo;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "promo_campaign_popup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PromoCampaignPopup {

    @Id
    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cta_target", nullable = false, length = 16)
    private PromoCtaTarget ctaTarget;

    @Column(name = "plan_id")
    private String packageId;

    @Column(name = "url")
    private String url;

    @Column(name = "image_url", nullable = false)
    private String imageUrl;

    @Column(name = "duration_sec", nullable = false)
    private Integer durationSec = 7;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "campaign_id")
    private PromoCampaign campaign;
}