package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobilePromoActiveItemResponse {

    private String code;
    private String label;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal discountAmount;
    private OffsetDateTime endsAt;
    private Integer priority;
    private MobilePromoBannerResponse banner;
    private MobilePromoPartnerResponse partner;
    private MobilePromoCtaResponse cta;
    private MobilePromoDetailResponse detail;
}
