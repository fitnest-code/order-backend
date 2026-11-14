package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignDiscount;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PricingMathTest {

    private static final ZoneOffset BAKU = ZoneOffset.ofHours(4);
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 11, 10, 0, 0, 0, 0, BAKU);
    private static final OffsetDateTime END = OffsetDateTime.of(2026, 11, 30, 23, 59, 59, 0, BAKU);

    static PricingCampaign campaign(PricingCampaignStatus status) {
        PricingCampaign c = new PricingCampaign();
        c.setStatus(status);
        c.setStartsAt(START);
        c.setEndsAt(END);
        return c;
    }

    static PricingCampaignDiscount tier(int months, String percent) {
        PricingCampaignDiscount d = new PricingCampaignDiscount();
        d.setMonths(months);
        d.setPercent(new BigDecimal(percent));
        return d;
    }

    @Test
    void finalPriceRoundsHalfUpToTwoDecimals() {
        // 33.33 * 0.80 = 26.664 -> 26.66 ; 10.05 * 0.75 = 7.5375 -> 7.54 ; 0.05 * 0.70 = 0.035 -> 0.04
        assertEquals(new BigDecimal("26.66"), PricingMath.finalPrice(new BigDecimal("33.33"), new BigDecimal("20")));
        assertEquals(new BigDecimal("7.54"), PricingMath.finalPrice(new BigDecimal("10.05"), new BigDecimal("25")));
        assertEquals(new BigDecimal("0.04"), PricingMath.finalPrice(new BigDecimal("0.05"), new BigDecimal("30")));
    }

    @Test
    void savedAmountAndMonthlyPrices() {
        BigDecimal list = new BigDecimal("120.00");
        BigDecimal fin = PricingMath.finalPrice(list, new BigDecimal("30"));
        assertEquals(new BigDecimal("84.00"), fin);
        assertEquals(new BigDecimal("36.00"), PricingMath.savedAmount(list, fin));
        assertEquals(new BigDecimal("7.00"), PricingMath.perMonth(fin, 12));
        assertEquals(new BigDecimal("10.00"), PricingMath.perMonth(list, 12));
        assertEquals(new BigDecimal("3.33"), PricingMath.perMonth(new BigDecimal("10.00"), 3));
    }

    @Test
    void percentForUsesTiersAndMonthOneIsZero() {
        List<PricingCampaignDiscount> tiers = List.of(tier(3, "20"), tier(6, "25"), tier(12, "30"));
        assertEquals(0, new BigDecimal("20").compareTo(PricingMath.percentFor(tiers, 3)));
        assertEquals(0, new BigDecimal("25").compareTo(PricingMath.percentFor(tiers, 6)));
        assertEquals(0, new BigDecimal("30").compareTo(PricingMath.percentFor(tiers, 12)));
        assertEquals(0, BigDecimal.ZERO.compareTo(PricingMath.percentFor(tiers, 1)));
        assertEquals(0, BigDecimal.ZERO.compareTo(PricingMath.percentFor(tiers, 9)));
        // Even a mis-configured month-1 tier must not discount a 1-month plan.
        assertEquals(0, BigDecimal.ZERO.compareTo(PricingMath.percentFor(List.of(tier(1, "50")), 1)));
    }

    @Test
    void windowBoundariesAreInclusiveAndStatusMustBeActive() {
        PricingCampaign active = campaign(PricingCampaignStatus.ACTIVE);
        active.setType(PricingCampaign.TYPE_BLACK_FRIDAY);
        assertTrue(PricingMath.isActiveAt(active, START));
        assertTrue(PricingMath.isActiveAt(active, END));
        assertFalse(PricingMath.isActiveAt(active, START.minusSeconds(1)));
        assertFalse(PricingMath.isActiveAt(active, END.plusSeconds(1)));
        // Same instant expressed in UTC must give the same answer (zone-independent).
        assertTrue(PricingMath.isActiveAt(active, START.withOffsetSameInstant(ZoneOffset.UTC)));

        for (PricingCampaignStatus s : List.of(PricingCampaignStatus.DRAFT, PricingCampaignStatus.DISABLED, PricingCampaignStatus.ENDED)) {
            PricingCampaign c = campaign(s);
            c.setType(PricingCampaign.TYPE_BLACK_FRIDAY);
            assertFalse(PricingMath.isActiveAt(c, START.plusDays(1)), s.name());
        }
        assertFalse(PricingMath.isActiveAt(null, START));
    }

    @Test
    void eligibilityCountdownAndNextPurchaseRules() {
        assertFalse(PricingMath.isEligible(true));
        assertTrue(PricingMath.isEligible(false));

        assertTrue(PricingMath.showCountdown(true, false));
        assertFalse(PricingMath.showCountdown(true, true));
        assertFalse(PricingMath.showCountdown(false, false));

        assertTrue(PricingMath.nextPurchaseAvailable(true, true, false, false));
        assertFalse(PricingMath.nextPurchaseAvailable(false, true, false, false));
        assertFalse(PricingMath.nextPurchaseAvailable(true, false, false, false));
        assertFalse(PricingMath.nextPurchaseAvailable(true, true, true, false));
        assertFalse(PricingMath.nextPurchaseAvailable(true, true, false, true));
    }

    @Test
    void fitCoinIsCappedByPriceAfterCampaign() {
        assertEquals(new BigDecimal("5.00"), PricingMath.fitCoinUsedAmount(new BigDecimal("84.00"), new BigDecimal("5")));
        assertEquals(new BigDecimal("84.00"), PricingMath.fitCoinUsedAmount(new BigDecimal("84.00"), new BigDecimal("200")));
        assertEquals(BigDecimal.ZERO, PricingMath.fitCoinUsedAmount(new BigDecimal("84.00"), BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, PricingMath.fitCoinUsedAmount(BigDecimal.ZERO, new BigDecimal("5")));
    }
}
