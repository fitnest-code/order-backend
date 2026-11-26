package az.fitnest.order.service.freeze;

import az.fitnest.order.model.entity.SubscriptionFreezeEntitlement;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionFreezeEntitlementTest {

    @Test
    void availableDaysNeverGoesBelowZero() {
        SubscriptionFreezeEntitlement entitlement = entitlement(10, 7, 5, "2026-11");

        assertEquals(0, entitlement.getAvailableDays());
        assertFalse(entitlement.canFreeze(1));
    }

    @Test
    void reserveAcceptsExactBalanceAndRejectsOverBalance() {
        SubscriptionFreezeEntitlement entitlement = entitlement(5, 1, 0, "2026-11");

        assertTrue(entitlement.canFreeze(4));
        entitlement.reserve(4);
        assertEquals(4, entitlement.getReservedDays());
        assertEquals(0, entitlement.getAvailableDays());

        assertThrows(IllegalStateException.class, () -> entitlement.reserve(1));
        assertEquals(4, entitlement.getReservedDays());
    }

    @Test
    void finaliseConsumedRejectsNegativeDaysWithoutMutation() {
        SubscriptionFreezeEntitlement entitlement = entitlement(10, 0, 5, "2026-11");

        assertThrows(IllegalArgumentException.class, () -> entitlement.finaliseConsumed(-1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> entitlement.finaliseConsumed(1, -1, true));

        assertEquals(0, entitlement.getConsumedDays());
        assertEquals(5, entitlement.getReservedDays());
    }

    @Test
    void finaliseConsumedChargesOnlyWhenRequested() {
        SubscriptionFreezeEntitlement charged = entitlement(10, 0, 5, "2026-11");
        charged.finaliseConsumed(3, 2, true);
        assertEquals(3, charged.getConsumedDays());
        assertEquals(0, charged.getReservedDays());
        assertEquals(7, charged.getAvailableDays());

        SubscriptionFreezeEntitlement notCharged = entitlement(10, 0, 5, "2026-11");
        notCharged.finaliseConsumed(3, 2, false);
        assertEquals(0, notCharged.getConsumedDays());
        assertEquals(0, notCharged.getReservedDays());
        assertEquals(10, notCharged.getAvailableDays());
    }

    @Test
    void finaliseConsumedClampsReservationAtZero() {
        SubscriptionFreezeEntitlement entitlement = entitlement(10, 0, 3, "2026-11");

        entitlement.finaliseConsumed(5, 0, true);

        assertEquals(0, entitlement.getReservedDays());
        assertEquals(5, entitlement.getConsumedDays());
    }

    @Test
    void allowanceMonthDetectionHandlesCurrentPreviousAndLegacyRows() {
        String currentMonth = YearMonth.now().toString();
        String previousMonth = YearMonth.now().minusMonths(1).toString();
        LocalDateTime currentStart = YearMonth.now().atDay(1).atStartOfDay();
        LocalDateTime previousStart = YearMonth.now().minusMonths(1).atDay(1).atStartOfDay();

        assertTrue(entitlement(10, 0, 0, currentMonth).belongsToAllowanceMonth(currentStart));
        assertFalse(entitlement(10, 0, 0, currentMonth).belongsToAllowanceMonth(previousStart));
        assertTrue(entitlement(10, 0, 0, previousMonth).belongsToAllowanceMonth(previousStart));
        assertTrue(entitlement(10, 0, 0, null).belongsToAllowanceMonth(previousStart));
        assertTrue(entitlement(10, 0, 0, currentMonth).belongsToAllowanceMonth(null));
    }

    private SubscriptionFreezeEntitlement entitlement(
            int totalDays, int consumedDays, int reservedDays, String allowanceMonth) {
        return SubscriptionFreezeEntitlement.builder()
                .subscriptionId(11L)
                .userId(22L)
                .totalDays(totalDays)
                .consumedDays(consumedDays)
                .reservedDays(reservedDays)
                .policyVersion("v1.2")
                .allowanceMonth(allowanceMonth)
                .build();
    }
}
