package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RevenueResponseDto(
        @JsonProperty("series") RevenueSeriesDto series,
        @JsonProperty("totals") RevenueTotalsDto totals
) {
}
