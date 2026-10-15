package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobilePromoPartnerResponse {

    private UUID id;
    private String name;
    private String logoUrl;
    private String logoOnDarkUrl;
    private String brandColor;
    private String textColor;
}
