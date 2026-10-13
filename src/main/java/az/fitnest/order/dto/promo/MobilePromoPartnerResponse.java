package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class MobilePromoPartnerResponse {

    private UUID id;
    private String name;
    private String logoUrl;
    private String brandColor;
    private String textColor;
}