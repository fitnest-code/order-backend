package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromoCampaignTranslationResponse {

    private String locale;
    private String popupTitle;
    private String popupBody;
    private String ctaLabel;
    private String cardLabel;
    private String notEligibleText;
    private String couponLabel;
}