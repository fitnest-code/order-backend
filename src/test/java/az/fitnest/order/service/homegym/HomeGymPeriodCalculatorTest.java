package az.fitnest.order.service.homegym;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R21 day boundaries and subscription-month arithmetic.
 */
class HomeGymPeriodCalculatorTest {

    private final HomeGymPeriodCalculator calculator = new HomeGymPeriodCalculator();

    @Test
    void dailyWindowIsTheBakuCalendarDay() { // R21 / T-35
        LocalDateTime moment = LocalDateTime.of(2026, 10, 27, 19, 0);

        assertEquals(LocalDateTime.of(2026, 10, 27, 0, 0), calculator.dayStart(moment));
        assertEquals(LocalDateTime.of(2026, 10, 28, 0, 0), calculator.nextDayStart(moment));
    }

    @Test
    void dailyWindowReopensJustAfterMidnight() { // T-36: 00:00:30 of the next day
        LocalDateTime moment = LocalDateTime.of(2026, 10, 28, 0, 0, 30);

        assertEquals(LocalDateTime.of(2026, 10, 28, 0, 0), calculator.dayStart(moment));
        assertEquals(LocalDateTime.of(2026, 10, 29, 0, 0), calculator.nextDayStart(moment));
    }

    @Test
    void periodStartsOnTheAnchorDayOfTheCurrentMonth() {
        LocalDateTime startAt = LocalDateTime.of(2026, 10, 5, 9, 0);

        assertEquals(LocalDate.of(2026, 10, 5),
                calculator.periodStart(startAt, LocalDate.of(2026, 10, 27)));
        assertEquals(LocalDate.of(2026, 11, 5),
                calculator.periodStart(startAt, LocalDate.of(2026, 11, 5)));
    }

    @Test
    void referenceBeforeTheAnchorStaysInTheFirstMonth() {
        LocalDateTime startAt = LocalDateTime.of(2026, 10, 5, 9, 0);

        assertEquals(LocalDate.of(2026, 10, 5), calculator.periodStart(startAt, LocalDate.of(2026, 10, 3)));
    }

    @Test
    void anchorDay31ClampsToTheLastDayOfAShortMonth() {
        LocalDateTime startAt = LocalDateTime.of(2026, 1, 31, 10, 0);

        // 15.02 is before the clamped 28.02 boundary, so the month still started on 31.01.
        assertEquals(LocalDate.of(2026, 1, 31), calculator.periodStart(startAt, LocalDate.of(2026, 2, 15)));
        // On 01.03 the February month (28.02 - 31.03) is active.
        assertEquals(LocalDate.of(2026, 2, 28), calculator.periodStart(startAt, LocalDate.of(2026, 3, 1)));
    }

    @Test
    void periodEndFollowsTheOriginalAnchor() {
        LocalDateTime startAt = LocalDateTime.of(2026, 1, 31, 10, 0);

        assertEquals(LocalDateTime.of(2026, 2, 28, 0, 0),
                calculator.periodEndsAt(startAt, LocalDate.of(2026, 1, 31)));
        assertEquals(LocalDateTime.of(2026, 3, 31, 0, 0),
                calculator.periodEndsAt(startAt, LocalDate.of(2026, 2, 28)));
    }

    @Test
    void nullSubscriptionFallsBackToCalendarMonth() {
        assertEquals(LocalDate.of(2026, 10, 1), calculator.periodStart(null, LocalDate.of(2026, 10, 27)));
        assertNull(calculator.periodEndsAt((LocalDate) null));
    }

    @Test
    void offsetsAreRenderedInBakuTime() {
        OffsetDateTime value = calculator.toOffset(LocalDateTime.of(2026, 10, 27, 0, 0));

        assertEquals(4 * 3600, value.getOffset().getTotalSeconds());
        assertEquals(LocalDateTime.of(2026, 10, 27, 0, 0), value.toLocalDateTime());
    }
}
