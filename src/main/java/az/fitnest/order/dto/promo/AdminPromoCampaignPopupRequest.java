package az.fitnest.order.dto.promo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class AdminPromoCampaignPopupRequest {

    @NotNull(message = "CTA target is required")
    private String ctaTarget; // PLANS, PLAN_DETAIL, URL, PROMO_DETAIL (M3)

    private String planId;

    private String url;

    @NotBlank(message = "Popup image URL is required")
    private String imageUrl;

    private String brandColorOverride;

    private String logoUrlOverride;

    private String logoOnDarkUrlOverride;

    @Builder.Default
    private Integer durationSec = 7;
}