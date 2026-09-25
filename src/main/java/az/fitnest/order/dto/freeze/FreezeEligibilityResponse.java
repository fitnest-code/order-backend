package az.fitnest.order.dto.freeze;

import java.time.LocalDateTime;

/**
 * Eligibility snapshot for the day-picker screen.
 * Field {@code currentlyFrozen} serializes as currentlyFrozen (not isCurrentlyFrozen).
 */
public record FreezeEligibilityResponse(
        boolean eligible,
        String reason,
        int totalDays,
        int consumedDays,
        int reservedDays,
        int availableDays,
        boolean currentlyFrozen,
        Long activeFreezeId,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
    public static FreezeEligibilityResponse denied(
            String reason, int totalDays, int consumedDays, int reservedDays, int availableDays,
            boolean currentlyFrozen, Long activeFreezeId, LocalDateTime startAt, LocalDateTime endAt) {
        return new FreezeEligibilityResponse(
                false, reason, totalDays, consumedDays, reservedDays, availableDays, currentlyFrozen, activeFreezeId, startAt, endAt);
    }

    public static FreezeEligibilityResponse ok(
            int totalDays, int consumedDays, int reservedDays, int availableDays, LocalDateTime startAt, LocalDateTime endAt) {
        return new FreezeEligibilityResponse(
                true, null, totalDays, consumedDays, reservedDays, availableDays, false, null, startAt, endAt);
    }
}
