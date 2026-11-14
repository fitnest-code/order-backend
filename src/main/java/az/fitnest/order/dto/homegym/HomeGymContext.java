package az.fitnest.order.dto.homegym;

import java.time.OffsetDateTime;

/**
 * Internal snapshot used by gRPC calls coming from catalog (gym listing) and by the
 * check-in engine.
 */
public record HomeGymContext(
        Long subscriptionId,
        Long userId,
        Long packageId,
        String subscriptionStatus,
        String entryMode,
        Long homeGymId,
        long homeVisitsThisPeriod,
        long standardVisitsThisPeriod,
        OffsetDateTime periodEndsAt,
        OffsetDateTime validUntil,
        boolean active) {
}
