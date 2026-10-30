package az.fitnest.order.dto.homegym;

import az.fitnest.order.model.enums.CheckInDecision;
import az.fitnest.order.model.enums.VisitEntryType;

import java.time.OffsetDateTime;

/**
 * Decision returned by order-backend to catalog (spec §"QR giriş: yeni sahələr").
 */
public record HomeGymCheckInResult(
        boolean success,
        CheckInDecision decision,
        String messageKey,
        VisitEntryType entryType,
        Long visitId,
        Long homeGymId,
        Long targetGymId,
        long standardVisitsUsed,
        int standardVisitsLimit,
        long homeVisitsThisPeriod,
        OffsetDateTime unlocksAt,
        OffsetDateTime renewsAt,
        String confirmationType,
        String confirmationToken,
        OffsetDateTime confirmationExpiresAt,
        String homeGymName,
        /** R21: earliest moment the subscriber may enter the home gym again (tomorrow 00:00). */
        OffsetDateTime nextHomeEntryAt) {

    public static HomeGymCheckInResult entered(CheckInDecision decision, VisitEntryType entryType, Long visitId,
                                                Long homeGymId, long standardVisitsUsed) {
        return new HomeGymCheckInResult(true, decision, null, entryType, visitId, homeGymId, null,
                standardVisitsUsed, 12, 0, null, null, null, null, null, null, null);
    }
}
