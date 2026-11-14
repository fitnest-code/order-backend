package az.fitnest.order.dto.homegym;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record HomeGymVisitResponse(
        Long visitId,
        Long gymId,
        String gymName,
        OffsetDateTime checkedInAt,
        String entryType,
        BigDecimal singlePrice) {
}
