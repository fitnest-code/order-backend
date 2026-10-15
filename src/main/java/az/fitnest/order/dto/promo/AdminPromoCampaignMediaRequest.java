package az.fitnest.order.dto.promo;

import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminPromoCampaignMediaRequest {

    @Pattern(regexp = "^(az|en|ru)$", message = "Locale must be az, en, or ru")
    private String locale;

    private String bannerImageUrl;

    private String heroImageUrl;
}
