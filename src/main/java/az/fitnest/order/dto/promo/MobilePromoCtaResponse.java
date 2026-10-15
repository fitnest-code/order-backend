package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class MobilePromoCtaResponse {

    private String label;
    private String target; // PLANS, PLAN_DETAIL, URL, PROMO_DETAIL (M3 partner screen)
    private String planId;
    private String url;
    private UUID campaignId;
    private String code;
}
