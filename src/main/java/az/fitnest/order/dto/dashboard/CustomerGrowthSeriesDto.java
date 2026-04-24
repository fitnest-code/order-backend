package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CustomerGrowthSeriesDto(
        @JsonProperty("metric") String metric,
        @JsonProperty("period") String period,
        @JsonProperty("points") List<ChartPointDto> points
) {
}
