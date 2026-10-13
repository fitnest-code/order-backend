package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class PromoQuotePromoResponse {

    private String code;
    private UUID campaignId;
    private String label;
    private BigDecimal discountAmount;
}