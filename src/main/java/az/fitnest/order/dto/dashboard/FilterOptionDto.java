package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FilterOptionDto(
        @JsonProperty("code") String code,
        @JsonProperty("title") String title
) {
}
