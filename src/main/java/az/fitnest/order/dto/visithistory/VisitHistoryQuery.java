package az.fitnest.order.dto.visithistory;

import az.fitnest.order.model.enums.VisitHistoryMode;
import az.fitnest.order.model.enums.VisitHistoryPreset;
import az.fitnest.order.model.enums.VisitHistoryStatus;

import java.time.LocalDate;

/** Parsed query parameters of {@code GET /api/v1/me/visits/history}. */
public record VisitHistoryQuery(
        VisitHistoryPreset preset,
        LocalDate from,
        LocalDate to,
        VisitHistoryStatus status,
        Long gymId,
        VisitHistoryMode mode,
        String cursor,
        int limit) {
}
