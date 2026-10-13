package az.fitnest.order.dto.promo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminPromoCampaignTranslationRequest {

    @NotBlank(message = "Locale is required")
    @Pattern(regexp = "^(az|en|ru)$", message = "Locale must be az, en, or ru")
    private String locale;

    @NotBlank(message = "Popup title is required for Azerbaijani")
    @Size(max = 40, message = "Popup title must be at most 40 characters")
    private String popupTitle;

    @NotBlank(message = "Popup body is required for Azerbaijani")
    @Size(max = 180, message = "Popup body must be at most 180 characters")
    private String popupBody;

    @Size(max = 24, message = "CTA label must be at most 24 characters")
    private String ctaLabel;

    @Size(max = 24, message = "Card label must be at most 24 characters")
    private String cardLabel;

    @Size(max = 120, message = "Not eligible text must be at most 120 characters")
    private String notEligibleText;

    @Size(max = 24, message = "Coupon label must be at most 24 characters")
    private String couponLabel;
}