package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobilePromoDetailResponse {

    private String heroImageUrl;
    private Integer durationSec;
    private MobilePromoCtaResponse cta;
}
