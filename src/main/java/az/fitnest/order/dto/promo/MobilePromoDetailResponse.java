package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MobilePromoDetailResponse {

    private String heroImageUrl;
    private Integer durationSec;
}
