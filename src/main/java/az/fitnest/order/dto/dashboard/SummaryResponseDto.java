package az.fitnest.order.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record SummaryResponseDto(
        @JsonProperty("cards") List<KpiCardDto> cards,
        @JsonProperty("generated_at") Instant generatedAt
) {
}
