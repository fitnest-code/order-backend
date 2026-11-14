package az.fitnest.order.dto.visithistory;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response of {@code GET /api/v1/me/visits/history} (spec "A. Tarixçə API").
 *
 * <p>Successful visits come from {@code gym_visits}, rejected attempts from
 * {@code visit_attempt}; both are merged by scan time. {@code rangeTotals} and
 * {@code gymsInRange} only honour the date range and {@code mode}, never the
 * {@code status}/{@code gymId} row filters.
 */
public record VisitHistoryResponse(
        Range range,
        SubscriptionPeriod subscriptionPeriod,
        RangeTotals rangeTotals,
        List<GymInRange> gymsInRange,
        List<Item> items,
        String nextCursor) {

    public record Range(
            String preset,
            LocalDate from,
            LocalDate to,
            long days) {
    }

    public record SubscriptionPeriod(
            @JsonProperty("package") String packageName,
            String mode,
            LocalDate periodStart,
            LocalDate periodEnd,
            int used,
            int limit,
            int remaining,
            HomeGym homeGym) {
    }

    public record HomeGym(
            Long gymId,
            String name,
            int used,
            int limit) {
    }

    public record RangeTotals(
            long success,
            long rejected,
            long distinctGyms) {
    }

    public record GymInRange(
            Long gymId,
            String name,
            long count) {
    }

    public record Item(
            String id,
            String type,
            Long gymId,
            String gymName,
            @JsonProperty("package") String packageName,
            OffsetDateTime scannedAt,
            String mode,
            String status,
            CounterAfter counterAfter,
            RejectReason rejectReason) {
    }

    public record CounterAfter(
            int used,
            int limit) {
    }

    public record RejectReason(
            String code,
            String text) {
    }
}
