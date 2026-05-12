package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record AdminSubscriptionPackageResponse(
    @JsonProperty("package_id")
    Long packageId,

    String name,

    @JsonProperty("is_active")
    Boolean isActive,

    @JsonProperty("sort_order")
    Integer sortOrder,

    @JsonProperty("entry_limit")
    Integer entryLimit,

    @JsonProperty("benefits")
    List<String> benefits,

    @JsonProperty("duration_options")
    List<AdminPackageOptionResponse> durationOptions
) {

    @Builder
    public record AdminPackageOptionResponse(
        @JsonProperty("option_id")
        Long optionId,

        @JsonProperty("duration_months")
        Integer durationMonths,

        @JsonProperty("price_standard")
        BigDecimal priceStandard,

        @JsonProperty("price_discounted")
        BigDecimal priceDiscounted,

        @JsonProperty("is_active")
        Boolean isActive,

        @JsonProperty("entry_limit")
        Integer entryLimit
    ) {}
}
