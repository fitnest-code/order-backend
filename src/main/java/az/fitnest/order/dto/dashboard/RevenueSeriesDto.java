package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record RevenueSeriesDto(
        @JsonProperty("metric") String metric,
        @JsonProperty("currency") String currency,
        @JsonProperty("package") String packageCode,
        @JsonProperty("period") String period,
        @JsonProperty("points") List<ChartPointDto> points
) {
}
