package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KpiCardDto(
        @JsonProperty("code") String code,
        @JsonProperty("title") String title,
        @JsonProperty("value") long value,
        @JsonProperty("delta_percent") double deltaPercent,
        @JsonProperty("delta_period") String deltaPeriod
) {
}
