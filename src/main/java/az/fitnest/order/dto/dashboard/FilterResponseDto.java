package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record FilterResponseDto(
        @JsonProperty("packages") List<FilterOptionDto> packages,
        @JsonProperty("periods") List<FilterOptionDto> periods,
        @JsonProperty("time_ranges") List<FilterOptionDto> timeRanges,
        @JsonProperty("defaults") Map<String, String> defaults
) {
}
