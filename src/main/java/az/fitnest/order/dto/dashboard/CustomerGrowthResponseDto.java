package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CustomerGrowthResponseDto(
        @JsonProperty("series") CustomerGrowthSeriesDto series
) {
}
