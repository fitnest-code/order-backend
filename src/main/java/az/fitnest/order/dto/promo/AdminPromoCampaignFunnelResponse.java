package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminPromoCampaignFunnelResponse {

    private Long eligible;
    private Long registered;
    private Long sawPopup;
    private Long selectedCode;
    private Long paid;
}