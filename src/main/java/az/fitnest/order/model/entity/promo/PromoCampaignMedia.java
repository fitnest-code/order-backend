package az.fitnest.order.model.entity.promo;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "promo_campaign_media")
@IdClass(PromoCampaignMediaId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PromoCampaignMedia {

    @Id
    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "locale", nullable = false, length = 8)
    private PromoLocale locale;

    @Column(name = "banner_image_url")
    private String bannerImageUrl;

    @Column(name = "hero_image_url")
    private String heroImageUrl;
}
