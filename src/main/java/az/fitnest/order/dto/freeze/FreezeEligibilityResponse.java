package az.fitnest.order.dto.freeze;

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
        Long activeFreezeId
) {
    public static FreezeEligibilityResponse denied(String reason, boolean currentlyFrozen, Long activeFreezeId) {
        return new FreezeEligibilityResponse(false, reason, 0, 0, 0, 0, currentlyFrozen, activeFreezeId);
    }

    public static FreezeEligibilityResponse deniedWithBalance(
            String reason, int totalDays, int consumedDays, int reservedDays, int availableDays) {
        return new FreezeEligibilityResponse(
                false, reason, totalDays, consumedDays, reservedDays, availableDays, false, null);
    }

    public static FreezeEligibilityResponse ok(
            int totalDays, int consumedDays, int reservedDays, int availableDays) {
        return new FreezeEligibilityResponse(
                true, null, totalDays, consumedDays, reservedDays, availableDays, false, null);
    }
}
