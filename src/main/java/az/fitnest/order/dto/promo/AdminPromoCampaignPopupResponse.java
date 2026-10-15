package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class AdminPromoCampaignPopupResponse {

    private String ctaTarget;
    private String packageId;
    private String url;
    private String imageUrl;
    private String brandColorOverride;
    private String logoUrlOverride;
    private String logoOnDarkUrlOverride;
    private Integer durationSec;
}