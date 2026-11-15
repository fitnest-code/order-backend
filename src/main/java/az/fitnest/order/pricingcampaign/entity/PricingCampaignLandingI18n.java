package az.fitnest.order.pricingcampaign.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Localized copy templates for the Black Friday landing screen. campaignId is a plain column (not a
 * relation): rows are only ever read by campaign, and the campaign aggregate stays untouched.
 * Placeholder rendering lives in PricingCampaignLandingService.
 */
@Entity
@Table(name = "pricing_campaign_landing_i18n")
@Getter
@Setter
@NoArgsConstructor
public class PricingCampaignLandingI18n {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** Lower-case: az | en | ru. */
    @Column(name = "locale", nullable = false, length = 2)
    private String locale;

    @Column(name = "title", nullable = false) private String title;
    @Column(name = "headline", nullable = false) private String headline;
    @Column(name = "subtitle", nullable = false, columnDefinition = "TEXT") private String subtitle;
    @Column(name = "option_label_template", nullable = false) private String optionLabelTemplate;
    @Column(name = "cta_title", nullable = false) private String ctaTitle;

    /** JSON array of strings, parsed by the service. */
    @Column(name = "terms_json", nullable = false, columnDefinition = "TEXT")
    private String termsJson = "[]";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
