package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChartPointDto(
        @JsonProperty("label") String label,
        @JsonProperty("value") Number value
) {
}
