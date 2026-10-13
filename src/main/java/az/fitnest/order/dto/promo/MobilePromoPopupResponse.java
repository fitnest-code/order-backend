package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class MobilePromoPopupResponse {

    private UUID grantId;
    private UUID campaignId;
    private String code;
    private String title;
    private String body;
    private String imageUrl;
    private OffsetDateTime endsAt;
    private MobilePromoCtaResponse cta;
    private MobilePromoPartnerResponse partner;
}