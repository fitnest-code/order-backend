package az.fitnest.order.dto.homegym;

import java.time.OffsetDateTime;

/**
 * One row of the home-gym history (spec §"Müştəri → abunəlik": active home gym, selection
 * date, source and history). Every selection, change and removal is a separate row.
 */
public record HomeGymHistoryItem(
        Long id,
        Long gymId,
        String gymName,
        String status,
        String source,
        OffsetDateTime selectedAt,
        OffsetDateTime endedAt,
        String endReason,
        OffsetDateTime endsAt) {
}
