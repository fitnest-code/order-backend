package az.fitnest.order.dto.homegym;

import java.time.OffsetDateTime;

/**
 * Response of {@code GET/PUT /api/v1/subscriptions/{id}/home-gym} (spec §"GET /home-gym cavabı").
 */
public record HomeGymResponse(
        Long subscriptionId,
        String entryMode,
        HomeGymInfo homeGym,
        boolean canChange,
        String changeBlockedReason,
        long homeVisitsThisPeriod,
        boolean otherGymsLocked,
        StandardVisits standardVisits,
        OffsetDateTime periodEndsAt,
        OffsetDateTime validUntil,
        boolean showHomeGymBanner) {

    public record HomeGymInfo(
            Long gymId,
            String name,
            OffsetDateTime selectedAt,
            String availableHours,
            OffsetDateTime endsAt) {
    }

    public record StandardVisits(int used, int limit, int remaining) {
    }
}
