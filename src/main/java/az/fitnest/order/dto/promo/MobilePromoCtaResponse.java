package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class MobilePromoCtaResponse {

    private String label;
    private String target; // PLANS, PLAN_DETAIL, URL
    private String planId;
    private String url;
}