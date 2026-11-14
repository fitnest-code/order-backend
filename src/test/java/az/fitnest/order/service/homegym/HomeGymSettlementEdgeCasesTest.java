package az.fitnest.order.service.homegym;

import az.fitnest.order.event.SubscriptionEvent;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.GymSettlementLine;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.enums.SettlementMode;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.repository.GymSettlementLineRepository;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Settlement mathematics around the 13-visit unlimited threshold (R15-R17): per-visit
 * fallback, the 12/13 boundary, the price version of the 13th home visit, majority/tie
 * package resolution, standard visits charged next to the unlimited price, idempotent
 * reruns, the monthly window and the refund/event-failure paths.
 */
class HomeGymSettlementEdgeCasesTest {

    private static final Long SUB_ID = 42L;
    private static final Long USER_ID = 7L;
    private static final Long HOME_GYM = 100L;
    private static final Long OTHER_GYM = 200L;
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 9, 5);

    private GymVisitRepository gymVisitRepository;
    private GymSettlementLineRepository settlementLineRepository;
    private SubscriptionRepository subscriptionRepository;
    private SubscriptionEventPublisher eventPublisher;
    private HomeGymSettlementService service;

    @BeforeEach
    void setUp() {
        gymVisitRepository = mock(GymVisitRepository.class);
        settlementLineRepository = mock(GymSettlementLineRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        eventPublisher = mock(SubscriptionEventPublisher.class);
        service = new HomeGymSettlementService(gymVisitRepository, settlementLineRepository,
                subscriptionRepository, new HomeGymPeriodCalculator(), eventPublisher);
    }

    // ------------------------------------------------------------------
    // 12 vs 13 home visits (R16)
    // ------------------------------------------------------------------

    @Test
    void twelveHomeVisitsAreChargedPerVisit() {
        List<GymVisit> visits = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            visits.add(homeVisit(i, HOME_GYM, "GOLD", "10.00", "50.00"));
        }

        int created = settleSinglePeriod(visits);

        assertEquals(1, created);
        GymSettlementLine line = savedLine();
        assertEquals(SettlementMode.PER_VISIT, line.getMode());
        assertEquals(new BigDecimal("120.00"), line.getAmount());
        assertEquals(12, line.getVisits());
        assertEquals(12, line.getHomeVisits());
        assertNull(line.getPricePackage());
        assertEquals("PER_VISIT", breakdown(line).get("rule"));
        assertEquals(12, breakdown(line).get("pricedVisits"));
    }

    @Test
    void thirteenHomeVisitsChargeOneUnlimitedPriceInsteadOfThirteenSingles() {
        List<GymVisit> visits = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            visits.add(homeVisit(i, HOME_GYM, "GOLD", "10.00", "50.00"));
        }

        int created = settleSinglePeriod(visits);

        assertEquals(1, created);
        GymSettlementLine line = savedLine();
        assertEquals(SettlementMode.UNLIMITED, line.getMode());
        assertEquals(new BigDecimal("50.00"), line.getAmount());
        assertEquals(13, line.getVisits());
        assertEquals(13, line.getHomeVisits());
        assertEquals("GOLD", line.getPricePackage());
        assertEquals("HOME_GYM_UNLIMITED", breakdown(line).get("rule"));
        assertEquals(new BigDecimal("50.00"), breakdown(line).get("unlimitedPrice"));
        assertEquals(13, breakdown(line).get("threshold"));
    }

    @Test
    void fourteenHomeVisitsStillChargeExactlyOneUnlimitedPrice() {
        List<GymVisit> visits = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            visits.add(homeVisit(i, HOME_GYM, "GOLD", "10.00", "50.00"));
        }

        settleSinglePeriod(visits);

        GymSettlementLine line = savedLine();
        assertEquals(SettlementMode.UNLIMITED, line.getMode());
        assertEquals(new BigDecimal("50.00"), line.getAmount());
    }

    // ------------------------------------------------------------------
    // Price version and package resolution (R16)
    // ------------------------------------------------------------------

    @Test
    void theUnlimitedPriceVersionOfTheThirteenthVisitIsUsed() {
        List<GymVisit> visits = new ArrayList<>();
        // Old version for visits 1-12, a new version starting on the 13th, and a later one.
        for (int i = 0; i < 12; i++) {
            visits.add(homeVisit(i, HOME_GYM, "GOLD", "10.00", "40.00"));
        }
        visits.add(homeVisit(12, HOME_GYM, "GOLD", "10.00", "50.00"));
        visits.add(homeVisit(13, HOME_GYM, "GOLD", "10.00", "55.00"));

        settleSinglePeriod(visits);

        GymSettlementLine line = savedLine();
        assertEquals(SettlementMode.UNLIMITED, line.getMode());
        // The version effective at the 13th visit, not the first and not the last visit.
        assertEquals(new BigDecimal("50.00"), line.getAmount());
    }

    @Test
    void theMajorityPackageOfTheHomeVisitsWins() {
        List<GymVisit> visits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            visits.add(homeVisit(i, HOME_GYM, "GOLD", "10.00", "45.00"));
        }
        for (int i = 8; i < 13; i++) {
            visits.add(homeVisit(i, HOME_GYM, "SILVER", "10.00", "70.00"));
        }

        settleSinglePeriod(visits);

        GymSettlementLine line = savedLine();
        assertEquals(SettlementMode.UNLIMITED, line.getMode());
        assertEquals("GOLD", line.getPricePackage());
        assertEquals(new BigDecimal("45.00"), line.getAmount());
    }

    @Test
    void aTieBetweenPackagesGoesToTheHigherUnlimitedPrice() {
        List<GymVisit> visits = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            visits.add(homeVisit(i, HOME_GYM, "GOLD", "10.00", "40.00"));
        }
        for (int i = 7; i < 14; i++) {
            visits.add(homeVisit(i, HOME_GYM, "SILVER", "10.00", "60.00"));
        }

        settleSinglePeriod(visits);

        GymSettlementLine line = savedLine();
        assertEquals("SILVER", line.getPricePackage());
        assertEquals(new BigDecimal("60.00"), line.getAmount());
    }

    @Test
    void visitsWithoutAPriceSnapshotDoNotProduceANegativeOrBrokenAmount() {
        List<GymVisit> visits = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            visits.add(homeVisit(i, HOME_GYM, "GOLD", null, null));
        }

        settleSinglePeriod(visits);

        GymSettlementLine line = savedLine();
        assertEquals(SettlementMode.UNLIMITED, line.getMode());
        assertEquals(new BigDecimal("0.00"), line.getAmount());
        assertEquals("GOLD", line.getPricePackage());
    }

    // ------------------------------------------------------------------
    // Standard visits next to the home gym (R16)
    // ------------------------------------------------------------------

    @Test
    void standardVisitsAtTheHomeGymStayChargedPerVisitNextToTheUnlimitedPrice() {
        List<GymVisit> visits = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            visits.add(homeVisit(i, HOME_GYM, "GOLD", "10.00", "50.00"));
        }
        visits.add(standardVisit(20, HOME_GYM, "7.50"));
        visits.add(standardVisit(21, HOME_GYM, "5.00"));

        settleSinglePeriod(visits);

        GymSettlementLine line = savedLine();
        assertEquals(SettlementMode.UNLIMITED, line.getMode());
        // 50.00 unlimited + 7.50 + 5.00 standard.
        assertEquals(new BigDecimal("62.50"), line.getAmount());
        assertEquals(15, line.getVisits());
        assertEquals(13, line.getHomeVisits());
        assertEquals(new BigDecimal("12.50"), breakdown(line).get("standardVisitsAmount"));
        assertEquals(2, breakdown(line).get("standardVisits"));
    }

    @Test
    void aHomeVisitWithoutAGymSnapshotIsPricedPerVisitButNotCountedAsHomeVisit() {
        GymVisit orphan = GymVisit.builder()
                .id(1L)
                .userId(USER_ID)
                .gymId(HOME_GYM)
                .subscriptionId(SUB_ID)
                .checkedInAt(PERIOD_START.atTime(10, 0))
                .entryType(VisitEntryType.HOME_UNLIMITED)
                .homeGymId(null)
                .pricePackage("GOLD")
                .singlePrice(new BigDecimal("10.00"))
                .unlimitedPrice(new BigDecimal("50.00"))
                .periodStart(PERIOD_START)
                .countsTowardsLimit(true)
                .build();

        settleSinglePeriod(List.of(orphan));

        GymSettlementLine line = savedLine();
        assertEquals(SettlementMode.PER_VISIT, line.getMode());
        assertEquals(1, line.getVisits());
        assertEquals(0, line.getHomeVisits());
        assertEquals(new BigDecimal("10.00"), line.getAmount());
    }

    // ------------------------------------------------------------------
    // Grouping, idempotency and window filtering
    // ------------------------------------------------------------------

    @Test
    void everyVisitedGymGetsItsOwnLine() {
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(subscription()));
        when(settlementLineRepository.findBySubscriptionId(SUB_ID)).thenReturn(List.of());
        when(gymVisitRepository.findDistinctPeriodsBySubscriptionId(SUB_ID))
                .thenReturn(List.of(PERIOD_START));
        when(gymVisitRepository.findBySubscriptionIdAndPeriodStartOrderByCheckedInAtAsc(
                SUB_ID, PERIOD_START)).thenReturn(List.of(
                standardVisit(1, HOME_GYM, "10.00"),
                standardVisit(2, OTHER_GYM, "5.00"),
                standardVisit(3, OTHER_GYM, "5.00")));
        when(settlementLineRepository.findByGymIdAndSubscriptionIdAndPeriodStart(
                anyLong(), anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());

        int created = service.resettleSubscription(SUB_ID);

        assertEquals(2, created);
        List<GymSettlementLine> lines = savedLines();
        assertEquals(2, lines.size());
        assertEquals(new BigDecimal("10.00"),
                lines.stream().filter(l -> l.getGymId().equals(HOME_GYM)).findFirst()
                        .orElseThrow().getAmount());
        assertEquals(new BigDecimal("10.00"),
                lines.stream().filter(l -> l.getGymId().equals(OTHER_GYM)).findFirst()
                        .orElseThrow().getAmount());
    }

    @Test
    void anAlreadySettledGymIsSkippedOnRerun() {
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(subscription()));
        when(settlementLineRepository.findBySubscriptionId(SUB_ID)).thenReturn(List.of());
        when(gymVisitRepository.findDistinctPeriodsBySubscriptionId(SUB_ID))
                .thenReturn(List.of(PERIOD_START));
        when(gymVisitRepository.findBySubscriptionIdAndPeriodStartOrderByCheckedInAtAsc(
                SUB_ID, PERIOD_START)).thenReturn(List.of(standardVisit(1, HOME_GYM, "10.00")));
        when(settlementLineRepository.findByGymIdAndSubscriptionIdAndPeriodStart(
                HOME_GYM, SUB_ID, PERIOD_START))
                .thenReturn(Optional.of(GymSettlementLine.builder().id(9L).build()));

        int created = service.resettleSubscription(SUB_ID);

        assertEquals(0, created);
        verify(settlementLineRepository, never()).save(any(GymSettlementLine.class));
        verify(eventPublisher, never()).publishSubscriptionEvent(anyLong(), any(), anyLong(), any());
    }

    @Test
    void visitsWithoutAGymIdCreateNoLine() {
        GymVisit anonymous = standardVisit(1, HOME_GYM, "10.00");
        anonymous.setGymId(null);

        int created = settleSinglePeriod(List.of(anonymous));

        assertEquals(0, created);
        verify(settlementLineRepository, never()).save(any(GymSettlementLine.class));
    }

    @Test
    void settleMonthOnlySettlesPeriodsWhoseLastDayFallsInTheSettledMonth() {
        YearMonth runMonth = YearMonth.of(2026, 11);
        when(gymVisitRepository.findDistinctSubscriptionPeriods(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 1)))
                .thenReturn(List.of(
                        new Object[]{SUB_ID, LocalDate.of(2026, 8, 5)},
                        new Object[]{SUB_ID, LocalDate.of(2026, 9, 5)},
                        new Object[]{SUB_ID, LocalDate.of(2026, 10, 5)},
                        new Object[]{"99", "2026-09-05"}));
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(subscription()));
        when(subscriptionRepository.findById(99L)).thenReturn(Optional.empty());
        when(gymVisitRepository.findBySubscriptionIdAndPeriodStartOrderByCheckedInAtAsc(
                SUB_ID, LocalDate.of(2026, 9, 5)))
                .thenReturn(List.of(standardVisit(1, HOME_GYM, "10.00")));
        when(settlementLineRepository.findByGymIdAndSubscriptionIdAndPeriodStart(
                anyLong(), anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());

        int created = service.settleMonth(runMonth);

        // Only 05.09-04.10 ends inside October; 05.08 already ended in September and
        // 05.10 ends in November; the string row points to a missing subscription.
        assertEquals(1, created);
        GymSettlementLine line = savedLine();
        assertEquals("2026-11", line.getRunMonth());
        assertEquals(LocalDate.of(2026, 9, 5), line.getPeriodStart());
        assertEquals(LocalDate.of(2026, 10, 4), line.getPeriodEnd());
    }

    @Test
    void settleMonthIgnoresRowsWithBrokenValues() {
        when(gymVisitRepository.findDistinctSubscriptionPeriods(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new Object[]{null, LocalDate.of(2026, 9, 5)},
                        new Object[]{"not-a-number", "2026-09-05"}));

        int created = service.settleMonth(YearMonth.of(2026, 11));

        assertEquals(0, created);
        verify(settlementLineRepository, never()).save(any(GymSettlementLine.class));
    }

    // ------------------------------------------------------------------
    // Refund / resettle and event tolerance
    // ------------------------------------------------------------------

    @Test
    void resettleDeletesPreviousLinesEvenWhenNoVisitsRemain() {
        GymSettlementLine stale = GymSettlementLine.builder().id(1L).gymId(HOME_GYM)
                .subscriptionId(SUB_ID).periodStart(PERIOD_START)
                .mode(SettlementMode.UNLIMITED).amount(new BigDecimal("99.00")).build();
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(subscription()));
        when(settlementLineRepository.findBySubscriptionId(SUB_ID)).thenReturn(List.of(stale));
        when(gymVisitRepository.findDistinctPeriodsBySubscriptionId(SUB_ID))
                .thenReturn(List.of(PERIOD_START));
        when(gymVisitRepository.findBySubscriptionIdAndPeriodStartOrderByCheckedInAtAsc(
                SUB_ID, PERIOD_START)).thenReturn(List.of());

        int created = service.resettleSubscription(SUB_ID);

        assertEquals(0, created);
        verify(settlementLineRepository).deleteAllInBatch(List.of(stale));
        verify(settlementLineRepository, never()).save(any(GymSettlementLine.class));
    }

    @Test
    void resettleForAMissingSubscriptionIsANoOp() {
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.empty());

        int created = service.resettleSubscription(SUB_ID);

        assertEquals(0, created);
        verify(settlementLineRepository, never()).deleteAllInBatch(any());
        verify(settlementLineRepository, never()).save(any(GymSettlementLine.class));
    }

    @Test
    void aFailingEventPublisherDoesNotLoseTheSettlementLine() {
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(eventPublisher).publishSubscriptionEvent(anyLong(), any(), anyLong(), any());

        int created = settleSinglePeriod(List.of(standardVisit(1, HOME_GYM, "10.00")));

        assertEquals(1, created);
        GymSettlementLine line = savedLine();
        assertEquals(new BigDecimal("10.00"), line.getAmount());
    }

    @Test
    void aCompletedLinePublishesTheSettlementEvent() {
        settleSinglePeriod(List.of(standardVisit(1, HOME_GYM, "10.00")));

        ArgumentCaptor<SubscriptionEvent> captor = ArgumentCaptor.forClass(SubscriptionEvent.class);
        verify(eventPublisher).publishSubscriptionEvent(eq(USER_ID), eq("SETTLEMENT_COMPLETED"),
                eq(SUB_ID), captor.capture());
        SubscriptionEvent event = captor.getValue();
        assertEquals(HOME_GYM, event.getGymId());
        assertEquals("10.00", event.getAmount());
        assertEquals("PER_VISIT", event.getMode());
        assertEquals(lineRunMonth(), event.getRunMonth());
    }

    @Test
    void settlementTimestampIsTakenFromThePeriodCalculator() {
        settleSinglePeriod(List.of(standardVisit(1, HOME_GYM, "10.00")));

        GymSettlementLine line = savedLine();
        assertTrue(line.getCreatedAt() != null);
        assertEquals("2026-09", line.getRunMonth());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int settleSinglePeriod(List<GymVisit> visits) {
        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.of(subscription()));
        when(settlementLineRepository.findBySubscriptionId(SUB_ID)).thenReturn(List.of());
        when(gymVisitRepository.findDistinctPeriodsBySubscriptionId(SUB_ID))
                .thenReturn(List.of(PERIOD_START));
        when(gymVisitRepository.findBySubscriptionIdAndPeriodStartOrderByCheckedInAtAsc(
                SUB_ID, PERIOD_START)).thenReturn(visits);
        when(settlementLineRepository.findByGymIdAndSubscriptionIdAndPeriodStart(
                anyLong(), anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());
        return service.resettleSubscription(SUB_ID);
    }

    private GymSettlementLine savedLine() {
        return savedLines().get(0);
    }

    private List<GymSettlementLine> savedLines() {
        ArgumentCaptor<GymSettlementLine> captor = ArgumentCaptor.forClass(GymSettlementLine.class);
        verify(settlementLineRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private static Map<String, Object> breakdown(GymSettlementLine line) {
        return line.getBreakdown();
    }

    private static String lineRunMonth() {
        return YearMonth.from(PERIOD_START).toString();
    }

    private static Subscription subscription() {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(SUB_ID);
        subscription.setUserId(USER_ID);
        subscription.setStartAt(PERIOD_START.atTime(9, 0));
        subscription.setEndAt(PERIOD_START.plusMonths(6).atTime(9, 0));
        return subscription;
    }

    private static GymVisit homeVisit(int index, Long gymId, String pricePackage,
                                      String singlePrice, String unlimitedPrice) {
        return GymVisit.builder()
                .id((long) index + 1)
                .userId(USER_ID)
                .gymId(gymId)
                .homeGymId(gymId)
                .subscriptionId(SUB_ID)
                .checkedInAt(visitTime(index))
                .entryType(VisitEntryType.HOME_UNLIMITED)
                .pricePackage(pricePackage)
                .singlePrice(decimal(singlePrice))
                .unlimitedPrice(decimal(unlimitedPrice))
                .periodStart(PERIOD_START)
                .countsTowardsLimit(true)
                .build();
    }

    private static GymVisit standardVisit(int index, Long gymId, String singlePrice) {
        return GymVisit.builder()
                .id((long) index + 100)
                .userId(USER_ID)
                .gymId(gymId)
                .subscriptionId(SUB_ID)
                .checkedInAt(visitTime(index))
                .entryType(VisitEntryType.STANDARD)
                .singlePrice(decimal(singlePrice))
                .periodStart(PERIOD_START)
                .countsTowardsLimit(true)
                .build();
    }

    private static LocalDateTime visitTime(int index) {
        return PERIOD_START.atTime(9, 0).plusHours(index);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
