package az.fitnest.order.pricingcampaign.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

public record AdminPricingCampaignRequest(
        @NotBlank String code,
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,
        @NotNull @Min(1) @Max(100) Integer maxPercent,
        @NotEmpty @Valid List<PricingDiscountDto> discounts,
        Localized pushTitle,
        Localized pushBody,
        Localized pushLast12hTitle,
        Localized pushLast12hBody) {

    public record Localized(String az, String ru, String en) {}
}
