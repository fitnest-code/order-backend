package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromoCampaignFunnelResponse {

    private Long eligible;
    private Long registered;
    private Long sawPopup;
    private Long selectedCode;
    private Long paid;
}