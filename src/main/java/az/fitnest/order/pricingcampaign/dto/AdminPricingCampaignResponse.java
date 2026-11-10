package az.fitnest.order.pricingcampaign.dto;

import az.fitnest.order.pricingcampaign.dto.AdminPricingCampaignRequest.Localized;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AdminPricingCampaignResponse(
        UUID id,
        String code,
        String type,
        String status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer maxPercent,
        List<PricingDiscountDto> discounts,
        Localized pushTitle,
        Localized pushBody,
        Localized pushLast12hTitle,
        Localized pushLast12hBody,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
