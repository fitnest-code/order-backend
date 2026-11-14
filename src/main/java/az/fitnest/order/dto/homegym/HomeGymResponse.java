package az.fitnest.order.dto.homegym;

import java.time.OffsetDateTime;

/**
 * Response of {@code GET/PUT /api/v1/subscriptions/{id}/home-gym} (spec §"GET /home-gym cavabı").
 *
 * <p>Version 3 (R19-R21): the home gym allows 30 visits per subscription month (1 per
 * calendar day), so the response exposes the monthly counter and the daily gate. When no
 * home gym is active the response also tells the client whether selecting one is currently
 * allowed and, when exactly one gym was visited in the period, suggests it (R19).
 */
public record HomeGymResponse(
        Long subscriptionId,
        String entryMode,
        HomeGymInfo homeGym,
        boolean canChange,
        String changeBlockedReason,
        long homeVisitsThisPeriod,
        int homeMonthlyLimit,
        int homeVisitsRemaining,
        boolean homeVisitedToday,
        OffsetDateTime nextHomeEntryAt,
        boolean otherGymsLocked,
        StandardVisits standardVisits,
        OffsetDateTime periodEndsAt,
        OffsetDateTime validUntil,
        boolean showHomeGymBanner,
        boolean canSelect,
        String selectBlockedReason,
        OffsetDateTime selectAvailableFrom,
        SuggestedHomeGym suggestedHomeGym) {

    public record HomeGymInfo(
            Long gymId,
            String name,
            OffsetDateTime selectedAt,
            String availableHours,
            OffsetDateTime endsAt,
            String source) {
    }

    public record StandardVisits(int used, int limit, int remaining) {
    }

    /**
     * R19: the only gym visited in the current subscription month (1-12 visits), when it
     * satisfies R1 (in the package and home-gym switch enabled).
     */
    public record SuggestedHomeGym(Long gymId, String name, long visitsThisPeriod) {
    }
}
