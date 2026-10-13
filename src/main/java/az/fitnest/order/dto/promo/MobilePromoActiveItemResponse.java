package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class MobilePromoActiveItemResponse {

    private String code;
    private String label;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal discountAmount;
    private OffsetDateTime endsAt;
    private MobilePromoBannerResponse banner;
    private MobilePromoPartnerResponse partner;
}