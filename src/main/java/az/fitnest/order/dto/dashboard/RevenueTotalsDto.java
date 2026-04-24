package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RevenueTotalsDto(
        @JsonProperty("sum") double sum
) {
}
