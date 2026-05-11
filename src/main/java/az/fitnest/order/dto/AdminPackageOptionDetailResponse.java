package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import java.math.BigDecimal;
import java.util.List;

@Builder
public record AdminPackageOptionDetailResponse(
    @JsonProperty("package_id")
    Long packageId,

    @JsonProperty("package_name")
    String packageName,

    @JsonProperty("option_id")
    Long optionId,

    @JsonProperty("duration_months")
    Integer durationMonths,

    @JsonProperty("price_standard")
    BigDecimal priceStandard,

    @JsonProperty("price_discounted")
    BigDecimal priceDiscounted,

    @JsonProperty("entry_limit")
    Integer entryLimit,

    @JsonProperty("is_active")
    Boolean isActive,

    @JsonProperty("benefits")
    List<String> benefits
) {}
