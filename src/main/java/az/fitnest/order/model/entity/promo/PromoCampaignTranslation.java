package az.fitnest.order.model.entity.promo;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "promo_campaign_translation")
@IdClass(PromoCampaignTranslationId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PromoCampaignTranslation {

    @Id
    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "locale", nullable = false, length = 8)
    private PromoLocale locale;

    @Column(name = "popup_title", nullable = false, length = 40)
    private String popupTitle;

    @Column(name = "popup_body", nullable = false, length = 180)
    private String popupBody;

    @Column(name = "cta_label", length = 24)
    private String ctaLabel = "Abunelik sec";

    @Column(name = "card_label", length = 24)
    private String cardLabel;

    @Column(name = "not_eligible_text", length = 120)
    private String notEligibleText;

    @Column(name = "coupon_label", length = 24)
    private String couponLabel;
}