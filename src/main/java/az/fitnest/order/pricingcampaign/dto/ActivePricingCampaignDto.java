package az.fitnest.order.pricingcampaign.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ActivePricingCampaignDto(
        UUID id,
        String code,
        String type,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer maxPercent,
        List<PricingDiscountDto> discounts,
        boolean eligible,
        boolean usedByUser,
        boolean showCountdown,
        NextPurchaseOfferDto nextPurchaseOffer) {}
