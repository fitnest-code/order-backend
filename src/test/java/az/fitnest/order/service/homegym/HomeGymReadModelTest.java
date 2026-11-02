package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.config.HomeGymLimits;
import az.fitnest.order.dto.homegym.HomeGymResponse;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.HomeGymSource;
import az.fitnest.order.model.enums.HomeGymStatus;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.HomeGymIdempotencyRepository;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Read model of the home gym: {@code GET /api/v1/subscriptions/{id}/home-gym} counters,
 * the daily gate (R21) and the R19/R20 selection hints, plus the entry-mode reconciliation
 * that keeps the stored mode aligned with subscription_home_gym rows.
 */
@ExtendWith(MockitoExtension.class)
class HomeGymReadModelTest {

    private static final HomeGymLimits.Snapshot LIMITS = new HomeGymLimits.Snapshot(30, 1, 12, 12);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 5);
    private static final LocalDateTime PERIOD_ENDS = LocalDateTime.of(2026, 11, 5, 0, 0);
    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 10, 20, 0, 0);
    private static final LocalDateTime NEXT_DAY_START = LocalDateTime.of(2026, 10, 21, 0, 0);

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionHomeGymRepository homeGymRepository;
    @Mock private GymVisitRepository gymVisitRepository;
    @Mock private HomeGymIdempotencyRepository idempotencyRepository;
    @Mock private CatalogServiceGrpcClient catalogClient;
    @Mock private HomeGymPeriodCalculator periodCalculator;
    @Mock private HomeGymSelectionPolicy selectionPolicy;
    @Mock private SubscriptionEventPublisher eventPublisher;
    @Mock private HomeGymSettlementService settlementService;
    @Mock private ObjectMapper objectMapper;
    @Mock private SubscriptionPackageRepository packageRepository;
    @Mock private HomeGymLimits homeGymLimits;

    @InjectMocks private HomeGymService service;

    @Test
    void activeHomeGymExposesCountersDailyGateAndOtherGymLock() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        subscription.setTotalLimit(20);
        SubscriptionHomeGym active = activeHomeGym();

        stubPeriod();
        when(homeGymLimits.snapshot()).thenReturn(LIMITS);
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findDistinctStatusesBySubscriptionId(5L))
                .thenReturn(List.of(HomeGymStatus.ACTIVE));
        when(homeGymRepository.findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                5L, HomeGymStatus.ACTIVE)).thenReturn(Optional.of(active));
        when(catalogClient.getHomeGymInfo(100L, 3L))
                .thenReturn(new CatalogServiceGrpcClient.HomeGymInfo(true, "FitZone", true, true, "06:00-23:00"));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(13L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.STANDARD)).thenReturn(2L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.STANDARD, 100L)).thenReturn(0L);
        when(gymVisitRepository.countCountableBySubscriptionAndGymAndTypeBetween(
                5L, 100L, VisitEntryType.HOME_UNLIMITED, DAY_START, NEXT_DAY_START)).thenReturn(1L);
        when(periodCalculator.dayStart(NOW)).thenReturn(DAY_START);
        when(periodCalculator.nextDayStart(NOW)).thenReturn(NEXT_DAY_START);

        var response = service.getHomeGym(9L, 5L);

        assertEquals("HOME_GYM", response.entryMode());
        assertEquals(100L, response.homeGym().gymId());
        assertEquals("FitZone", response.homeGym().name());
        assertEquals("06:00-23:00", response.homeGym().availableHours());
        assertEquals(offset(LocalDateTime.of(2026, 10, 6, 10, 0)), response.homeGym().selectedAt());
        assertEquals(13, response.homeVisitsThisPeriod());
        assertEquals(30, response.homeMonthlyLimit());
        assertEquals(17, response.homeVisitsRemaining());
        assertTrue(response.homeVisitedToday());
        assertEquals(offset(NEXT_DAY_START), response.nextHomeEntryAt());
        assertTrue(response.otherGymsLocked());
        // R4: a visit in the current month blocks changing the home gym.
        assertFalse(response.canChange());
        assertEquals("HOME_GYM_HAS_VISITS", response.changeBlockedReason());
        // Subscription snapshot wins over the configured STANDARD limit.
        assertEquals(new HomeGymResponse.StandardVisits(2, 20, 18),
                response.standardVisits());
        assertEquals(offset(PERIOD_ENDS), response.periodEndsAt());
        assertEquals(offset(LocalDateTime.of(2026, 12, 5, 9, 0)), response.validUntil());
        assertFalse(response.showHomeGymBanner());
        assertFalse(response.canSelect());
        assertNull(response.suggestedHomeGym());
    }

    @Test
    void staleHomeModeWithoutHistoryResetsToPendingAndOffersSelection() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);

        stubPeriod();
        when(homeGymLimits.snapshot()).thenReturn(LIMITS);
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findDistinctStatusesBySubscriptionId(5L)).thenReturn(List.of());
        when(homeGymRepository.findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                5L, HomeGymStatus.ACTIVE)).thenReturn(Optional.empty());
        when(selectionPolicy.evaluate(5L, PERIOD_START))
                .thenReturn(new HomeGymSelectionPolicy.SelectionState(true, null, null, 0, 0, Map.of()));

        var response = service.getHomeGym(9L, 5L);

        assertEquals(EntryMode.HOME_GYM_PENDING, subscription.getEntryMode());
        verify(subscriptionRepository).save(subscription);
        assertEquals("HOME_GYM_PENDING", response.entryMode());
        assertNull(response.homeGym());
        assertTrue(response.canSelect());
        assertTrue(response.showHomeGymBanner());
        assertNull(response.selectBlockedReason());
        assertNull(response.selectAvailableFrom());
        assertNull(response.suggestedHomeGym());
        assertEquals(0, response.homeVisitsThisPeriod());
        assertEquals(30, response.homeVisitsRemaining());
        assertFalse(response.otherGymsLocked());
        assertFalse(response.homeVisitedToday());
        assertNull(response.nextHomeEntryAt());
        assertEquals(new HomeGymResponse.StandardVisits(0, 12, 12),
                response.standardVisits());
    }

    @Test
    void endedHomeGymRepairsToStandardAndCountsHomeVisitsAsStandard() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);

        stubPeriod();
        when(homeGymLimits.snapshot()).thenReturn(LIMITS);
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findDistinctStatusesBySubscriptionId(5L))
                .thenReturn(List.of(HomeGymStatus.ENDED));
        when(homeGymRepository.findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                5L, HomeGymStatus.ACTIVE)).thenReturn(Optional.empty());
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.STANDARD)).thenReturn(3L);
        // STANDARD mode counts every home visit against the standard allowance (R7).
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED)).thenReturn(2L);
        when(selectionPolicy.evaluate(5L, PERIOD_START)).thenReturn(new HomeGymSelectionPolicy.SelectionState(
                false, HomeGymSelectionPolicy.MULTIPLE_GYMS_THIS_PERIOD, null, 0, 5, Map.of(1L, 3L, 2L, 2L)));

        var response = service.getHomeGym(9L, 5L);

        assertEquals(EntryMode.STANDARD, subscription.getEntryMode());
        verify(subscriptionRepository).save(subscription);
        assertEquals("STANDARD", response.entryMode());
        assertEquals(new HomeGymResponse.StandardVisits(5, 12, 7),
                response.standardVisits());
        assertFalse(response.canSelect());
        assertEquals(HomeGymSelectionPolicy.MULTIPLE_GYMS_THIS_PERIOD, response.selectBlockedReason());
        assertEquals(offset(PERIOD_ENDS), response.selectAvailableFrom());
        assertFalse(response.showHomeGymBanner());
    }

    @Test
    void soleVisitedGymIsSuggestedWhenEligible() {
        Subscription subscription = subscription(EntryMode.HOME_GYM_PENDING);

        stubPeriod();
        when(homeGymLimits.snapshot()).thenReturn(LIMITS);
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findDistinctStatusesBySubscriptionId(5L)).thenReturn(List.of());
        when(homeGymRepository.findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                5L, HomeGymStatus.ACTIVE)).thenReturn(Optional.empty());
        when(selectionPolicy.evaluate(5L, PERIOD_START))
                .thenReturn(new HomeGymSelectionPolicy.SelectionState(
                        true, null, 7L, 4, 4, Map.of(7L, 4L)));
        when(catalogClient.getHomeGymInfo(7L, 3L))
                .thenReturn(new CatalogServiceGrpcClient.HomeGymInfo(true, "NeoGym", true, true, null));

        var response = service.getHomeGym(9L, 5L);

        assertTrue(response.canSelect());
        assertTrue(response.showHomeGymBanner());
        assertEquals(7L, response.suggestedHomeGym().gymId());
        assertEquals("NeoGym", response.suggestedHomeGym().name());
        assertEquals(4, response.suggestedHomeGym().visitsThisPeriod());
        assertNull(response.selectBlockedReason());
    }

    @Test
    void soleVisitedGymOutsideThePackageBlocksSelection() {
        Subscription subscription = subscription(EntryMode.HOME_GYM_PENDING);

        stubPeriod();
        when(homeGymLimits.snapshot()).thenReturn(LIMITS);
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findDistinctStatusesBySubscriptionId(5L)).thenReturn(List.of());
        when(homeGymRepository.findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                5L, HomeGymStatus.ACTIVE)).thenReturn(Optional.empty());
        when(selectionPolicy.evaluate(5L, PERIOD_START))
                .thenReturn(new HomeGymSelectionPolicy.SelectionState(
                        true, null, 7L, 4, 4, Map.of(7L, 4L)));
        when(catalogClient.getHomeGymInfo(7L, 3L))
                .thenReturn(new CatalogServiceGrpcClient.HomeGymInfo(true, "NeoGym", false, true, null));

        var response = service.getHomeGym(9L, 5L);

        assertFalse(response.canSelect());
        assertFalse(response.showHomeGymBanner());
        assertEquals("GYM_NOT_IN_PACKAGE", response.selectBlockedReason());
        assertEquals(offset(PERIOD_ENDS), response.selectAvailableFrom());
        assertNull(response.suggestedHomeGym());
    }

    @Test
    void subscriberUserIdsAreDeduplicatedInDiscoveryOrder() {
        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of(1L, 2L, 3L));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(subscriptionOf(1L, 9L)));
        when(subscriptionRepository.findById(2L)).thenReturn(Optional.of(subscriptionOf(2L, 9L)));
        when(subscriptionRepository.findById(3L)).thenReturn(Optional.of(subscriptionOf(3L, 10L)));

        assertEquals(List.of(9L, 10L), service.resolveSubscriberUserIds(100L));
    }

    private void stubPeriod() {
        when(periodCalculator.now()).thenReturn(NOW);
        when(periodCalculator.periodStart(any(LocalDateTime.class), any(LocalDate.class)))
                .thenReturn(PERIOD_START);
        when(periodCalculator.periodEndsAt(any(LocalDateTime.class), any(LocalDate.class)))
                .thenReturn(PERIOD_ENDS);
        when(periodCalculator.toOffset(nullable(LocalDateTime.class)))
                .thenAnswer(invocation -> offset(invocation.getArgument(0)));
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(HomeGymPeriodCalculator.BAKU).toOffsetDateTime();
    }

    private static Subscription subscription(EntryMode entryMode) {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(5L);
        subscription.setUserId(9L);
        subscription.setPackageId(3L);
        subscription.setStartAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        subscription.setEndAt(LocalDateTime.of(2026, 12, 5, 9, 0));
        subscription.setStatus("ACTIVE");
        subscription.setEntryMode(entryMode);
        return subscription;
    }

    private static Subscription subscriptionOf(Long subscriptionId, Long userId) {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(subscriptionId);
        subscription.setUserId(userId);
        return subscription;
    }

    private static SubscriptionHomeGym activeHomeGym() {
        return SubscriptionHomeGym.builder()
                .id(1L)
                .subscriptionId(5L)
                .userId(9L)
                .gymId(100L)
                .status(HomeGymStatus.ACTIVE)
                .source(HomeGymSource.SUBSCRIPTION_SCREEN)
                .selectedAt(LocalDateTime.of(2026, 10, 6, 10, 0))
                .build();
    }
}
