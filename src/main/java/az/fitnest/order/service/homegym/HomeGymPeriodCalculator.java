package az.fitnest.order.service.homegym;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.YearMonth;

/**
 * Subscription-month arithmetic for home-gym counters and settlement.
 *
 * <p>A subscription month starts on the subscription start day (e.g. 05.10) and ends
 * on the same day of the next month at 00:00 (e.g. 05.11). Dates are evaluated in
 * Asia/Baku, matching the rest of the order service.
 */
@Component
public class HomeGymPeriodCalculator {

    public static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    public static final int DEFAULT_STANDARD_LIMIT = 12;

    public LocalDateTime now() {
        return LocalDateTime.now(BAKU);
    }

    /** Start date of the subscription month containing {@code reference}. */
    public LocalDate periodStart(LocalDateTime subscriptionStartAt, LocalDate reference) {
        if (subscriptionStartAt == null) {
            return reference.withDayOfMonth(1);
        }
        LocalDate anchor = subscriptionStartAt.toLocalDate();
        if (reference.isBefore(anchor)) {
            return anchor;
        }
        LocalDate candidate = anchor.withYear(reference.getYear()).withMonth(reference.getMonthValue());
        if (candidate.isAfter(reference) || candidate.getDayOfMonth() != anchor.getDayOfMonth()) {
            // Short month: use the last day of the month, then fall back to the previous month
            // when that would still be in the future.
            candidate = clampToMonth(anchor.getDayOfMonth(), reference.getYear(), reference.getMonthValue());
            if (candidate.isAfter(reference)) {
                LocalDate previous = reference.minusMonths(1);
                candidate = clampToMonth(anchor.getDayOfMonth(), previous.getYear(), previous.getMonthValue());
            }
        }
        if (candidate.isBefore(anchor)) {
            return anchor;
        }
        return candidate;
    }

    public LocalDate periodStart(LocalDateTime subscriptionStartAt) {
        return periodStart(subscriptionStartAt, now().toLocalDate());
    }

    /** Exclusive end of the subscription month that started on {@code periodStart}. */
    public LocalDateTime periodEndsAt(LocalDate periodStart) {
        if (periodStart == null) {
            return null;
        }
        LocalDate next = clampToMonth(periodStart.getDayOfMonth(),
                periodStart.plusMonths(1).getYear(), periodStart.plusMonths(1).getMonthValue());
        if (!next.isAfter(periodStart)) {
            next = periodStart.plusMonths(1);
        }
        return next.atStartOfDay();
    }

    /**
     * Exclusive end of the subscription month containing {@code periodStart}, anchored on the
     * original subscription day. This keeps the end date aligned with
     * {@link #periodStart(LocalDateTime, LocalDate)} for subscriptions that start on the 29th-31st
     * and therefore have clamped monthly boundaries.
     */
    public LocalDateTime periodEndsAt(LocalDateTime subscriptionStartAt, LocalDate periodStart) {
        if (subscriptionStartAt == null || periodStart == null) {
            return periodEndsAt(periodStart);
        }
        int anchorDay = subscriptionStartAt.getDayOfMonth();
        YearMonth month = YearMonth.from(periodStart).plusMonths(1);
        LocalDate next = clampToMonth(anchorDay, month.getYear(), month.getMonthValue());
        while (!next.isAfter(periodStart)) {
            month = month.plusMonths(1);
            next = clampToMonth(anchorDay, month.getYear(), month.getMonthValue());
        }
        return next.atStartOfDay();
    }

    public LocalDateTime periodEndsAt(LocalDateTime subscriptionStartAt) {
        return periodEndsAt(subscriptionStartAt, periodStart(subscriptionStartAt));
    }

    public OffsetDateTime toOffset(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atZone(BAKU).toOffsetDateTime();
    }

    public OffsetDateTime nowOffset() {
        return OffsetDateTime.now(BAKU);
    }

    public boolean isExpired(LocalDateTime endAt) {
        return endAt != null && endAt.isBefore(now());
    }

    private LocalDate clampToMonth(int dayOfMonth, int year, int month) {
        LocalDate first = LocalDate.of(year, month, 1);
        return first.withDayOfMonth(Math.min(dayOfMonth, first.lengthOfMonth()));
    }

    /** Convenience for tests/integration: parse an ISO offset or Baku local timestamp. */
    public LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(BAKU).toLocalDateTime();
        } catch (Exception ignored) {
            return LocalDateTime.parse(value);
        }
    }

    /** Fixed +04:00 offset helper for services that need a stable offset. */
    public ZoneOffset bakuOffset() {
        return BAKU.getRules().getOffset(now());
    }
}
