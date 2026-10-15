package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminPromoCampaignMediaResponse {

    private String locale;
    private String bannerImageUrl;
    private String heroImageUrl;
}
