package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.config.HomeGymLimits;
import az.fitnest.order.event.SubscriptionEvent;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.HomeGymEndReason;
import az.fitnest.order.model.enums.HomeGymSource;
import az.fitnest.order.model.enums.HomeGymStatus;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.HomeGymIdempotencyRepository;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Scheduled lifecycle of the home gym (R18, subscription end, F-04 refund fan-out) and the
 * admin subscriber listing: which rows are ended, which are kept, and what is published.
 */
@ExtendWith(MockitoExtension.class)
class HomeGymLifecycleJobsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 3, 0);
    private static final LocalDateTime PERIOD_ENDS = LocalDateTime.of(2026, 11, 5, 0, 0);

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

    @BeforeEach
    void baseStubs() {
        lenient().when(periodCalculator.now()).thenReturn(NOW);
        lenient().when(periodCalculator.periodEndsAt(any(LocalDateTime.class))).thenReturn(PERIOD_ENDS);
        lenient().when(catalogClient.getHomeGymInfo(anyLong(), nullable(Long.class)))
                .thenReturn(new CatalogServiceGrpcClient.HomeGymInfo(true, "FitZone", true, true, null));
        lenient().when(homeGymRepository.save(any(SubscriptionHomeGym.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------
    // expireDueHomeGyms (R18: gym switch off / package removal at month end)
    // ------------------------------------------------------------------

    @Test
    void dueRowIsEndedWithItsScheduledReason() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "ACTIVE");
        SubscriptionHomeGym due = activeRow(100L);
        due.setEndsAt(PERIOD_ENDS.minusDays(1));
        due.setEndReason(HomeGymEndReason.GYM_HOME_DISABLED);

        when(homeGymRepository.findDueForScheduledEnd(NOW)).thenReturn(List.of(due));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));

        service.expireDueHomeGyms();

        assertEquals(HomeGymStatus.ENDED, due.getStatus());
        assertEquals(NOW, due.getEndedAt());
        assertEquals(HomeGymEndReason.GYM_HOME_DISABLED, due.getEndReason());
        assertNull(due.getEndsAt());
        assertEquals(EntryMode.STANDARD, subscription.getEntryMode());
        verify(subscriptionRepository).save(subscription);
        SubscriptionEvent event = captureSingleEvent();
        assertEquals(5L, event.getSubscriptionId());
        assertEquals(100L, event.getGymId());
        assertEquals("FitZone", event.getGymName());
        assertEquals("GYM_HOME_DISABLED", event.getEndReason());
    }

    @Test
    void dueRowWithoutAReasonFallsBackToGymHomeDisabled() {
        SubscriptionHomeGym due = activeRow(100L);
        due.setEndsAt(PERIOD_ENDS.minusDays(1));
        // No end reason (legacy row).
        when(homeGymRepository.findDueForScheduledEnd(NOW)).thenReturn(List.of(due));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription(EntryMode.HOME_GYM, "ACTIVE")));

        service.expireDueHomeGyms();

        assertEquals(HomeGymEndReason.GYM_HOME_DISABLED, due.getEndReason());
        assertEquals("GYM_HOME_DISABLED", captureSingleEvent().getEndReason());
    }

    @Test
    void dueRowKeepsAnExplicitRefundReason() {
        SubscriptionHomeGym due = activeRow(100L);
        due.setEndReason(HomeGymEndReason.REFUND);
        when(homeGymRepository.findDueForScheduledEnd(NOW)).thenReturn(List.of(due));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription(EntryMode.HOME_GYM, "ACTIVE")));

        service.expireDueHomeGyms();

        assertEquals(HomeGymEndReason.REFUND, due.getEndReason());
        assertEquals("REFUND", captureSingleEvent().getEndReason());
    }

    @Test
    void dueRowWhoseSubscriptionIsGoneIsLeftUntouched() {
        SubscriptionHomeGym due = activeRow(100L);
        due.setEndsAt(PERIOD_ENDS.minusDays(1));
        when(homeGymRepository.findDueForScheduledEnd(NOW)).thenReturn(List.of(due));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.empty());

        service.expireDueHomeGyms();

        assertEquals(HomeGymStatus.ACTIVE, due.getStatus());
        verify(homeGymRepository, never()).save(any(SubscriptionHomeGym.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void noDueRowsPublishNothing() {
        when(homeGymRepository.findDueForScheduledEnd(NOW)).thenReturn(List.of());

        service.expireDueHomeGyms();

        verifyNoInteractions(subscriptionRepository, eventPublisher);
    }

    // ------------------------------------------------------------------
    // expireEndedSubscriptionHomeGyms (subscription end)
    // ------------------------------------------------------------------

    @Test
    void expiredSubscriptionEndsTheHomeGym() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "FINISHED");
        subscription.setEndAt(NOW.minusDays(1));
        SubscriptionHomeGym row = activeRow(100L);
        when(homeGymRepository.findByStatusAndEndReasonIsNull(HomeGymStatus.ACTIVE))
                .thenReturn(List.of(row));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));

        service.expireEndedSubscriptionHomeGyms();

        assertEquals(HomeGymStatus.ENDED, row.getStatus());
        assertEquals(HomeGymEndReason.SUBSCRIPTION_ENDED, row.getEndReason());
        assertEquals(EntryMode.STANDARD, subscription.getEntryMode());
        assertEquals("SUBSCRIPTION_ENDED", captureSingleEvent().getEndReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "EXPIRED"})
    void terminalSubscriptionStatesEndTheHomeGymEvenBeforeTheEndDate(String status) {
        Subscription subscription = subscription(EntryMode.HOME_GYM, status);
        subscription.setEndAt(NOW.plusDays(20));
        SubscriptionHomeGym row = activeRow(100L);
        when(homeGymRepository.findByStatusAndEndReasonIsNull(HomeGymStatus.ACTIVE))
                .thenReturn(List.of(row));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));

        service.expireEndedSubscriptionHomeGyms();

        assertEquals(HomeGymStatus.ENDED, row.getStatus());
        assertEquals(HomeGymEndReason.SUBSCRIPTION_ENDED, row.getEndReason());
    }

    @Test
    void frozenSubscriptionKeepsTheHomeGym() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "FROZEN");
        subscription.setEndAt(NOW.plusDays(10));
        SubscriptionHomeGym row = activeRow(100L);
        when(homeGymRepository.findByStatusAndEndReasonIsNull(HomeGymStatus.ACTIVE))
                .thenReturn(List.of(row));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));

        service.expireEndedSubscriptionHomeGyms();

        assertEquals(HomeGymStatus.ACTIVE, row.getStatus());
        assertNull(row.getEndedAt());
        verify(homeGymRepository, never()).save(any(SubscriptionHomeGym.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aFrozenSubscriptionPastItsEndDateStillEndsTheHomeGym() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "FROZEN");
        subscription.setEndAt(NOW.minusDays(1));
        SubscriptionHomeGym row = activeRow(100L);
        when(homeGymRepository.findByStatusAndEndReasonIsNull(HomeGymStatus.ACTIVE))
                .thenReturn(List.of(row));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));

        service.expireEndedSubscriptionHomeGyms();

        assertEquals(HomeGymStatus.ENDED, row.getStatus());
        assertEquals(HomeGymEndReason.SUBSCRIPTION_ENDED, row.getEndReason());
    }

    @Test
    void finishedSubscriptionBeforeItsEndDateKeepsUnlimitedHomeAccess() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "FINISHED");
        subscription.setEndAt(NOW.plusDays(10));
        SubscriptionHomeGym row = activeRow(100L);
        when(homeGymRepository.findByStatusAndEndReasonIsNull(HomeGymStatus.ACTIVE))
                .thenReturn(List.of(row));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));

        service.expireEndedSubscriptionHomeGyms();

        assertEquals(HomeGymStatus.ACTIVE, row.getStatus());
        assertEquals(EntryMode.HOME_GYM, subscription.getEntryMode());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void activeSubscriptionWithoutAnEndDateKeepsTheHomeGym() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "ACTIVE");
        subscription.setEndAt(null);
        SubscriptionHomeGym row = activeRow(100L);
        when(homeGymRepository.findByStatusAndEndReasonIsNull(HomeGymStatus.ACTIVE))
                .thenReturn(List.of(row));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));

        service.expireEndedSubscriptionHomeGyms();

        assertEquals(HomeGymStatus.ACTIVE, row.getStatus());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void subscriptionExpiryJobSkipsRowsWithoutASubscription() {
        SubscriptionHomeGym row = activeRow(100L);
        when(homeGymRepository.findByStatusAndEndReasonIsNull(HomeGymStatus.ACTIVE))
                .thenReturn(List.of(row));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.empty());

        service.expireEndedSubscriptionHomeGyms();

        assertEquals(HomeGymStatus.ACTIVE, row.getStatus());
        verify(homeGymRepository, never()).save(any(SubscriptionHomeGym.class));
    }

    // ------------------------------------------------------------------
    // onGymHomeDisabled (R18)
    // ------------------------------------------------------------------

    @Test
    void gymSwitchOffSchedulesTheEndAtTheMonthBoundaryWithoutEndingNow() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "ACTIVE");
        SubscriptionHomeGym row = activeRow(100L);
        Map<String, String> title = Map.of("az", "Zal baglandi");
        Map<String, String> body = Map.of("az", "Esas zal secin");
        List<Long> recipients = List.of(9L);

        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of(5L));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(row));

        service.onGymHomeDisabled(100L, 77L, title, body, recipients);

        assertEquals(HomeGymStatus.ACTIVE, row.getStatus());
        assertEquals(PERIOD_ENDS, row.getEndsAt());
        assertEquals(HomeGymEndReason.GYM_HOME_DISABLED, row.getEndReason());
        verify(homeGymRepository).save(row);
        // The subscription keeps its home-gym mode until the scheduled end runs.
        assertEquals(EntryMode.HOME_GYM, subscription.getEntryMode());

        SubscriptionEvent event = captureSingleEvent();
        assertEquals("HOME_GYM_DISABLED_BY_GYM", event.getAction());
        assertEquals(100L, event.getGymId());
        assertEquals(77L, event.getCampaignId());
        assertEquals(title, event.getTitle());
        assertEquals(body, event.getBody());
        assertEquals(recipients, event.getRecipients());
    }

    @Test
    void gymSwitchOffSkipsSubscribersWhoseActiveGymChanged() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "ACTIVE");
        SubscriptionHomeGym anotherGym = activeRow(101L);
        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of(5L));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(anotherGym));

        service.onGymHomeDisabled(100L, null, null, null, null);

        assertNull(anotherGym.getEndsAt());
        assertNull(anotherGym.getEndReason());
        verify(homeGymRepository, never()).save(any(SubscriptionHomeGym.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void gymSwitchOffSkipsMissingSubscriptionAndMissingActiveRow() {
        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of(5L, 6L));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.empty());
        when(subscriptionRepository.findById(6L))
                .thenReturn(Optional.of(subscription(EntryMode.HOME_GYM, "ACTIVE")));
        when(homeGymRepository.findActiveForUpdate(6L)).thenReturn(Optional.empty());

        service.onGymHomeDisabled(100L, null, null, null, null);

        verify(homeGymRepository, never()).save(any(SubscriptionHomeGym.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void gymSwitchOffWithNoSubscribersDoesNothing() {
        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of());

        service.onGymHomeDisabled(100L, null, null, null, null);

        verifyNoInteractions(subscriptionRepository, eventPublisher);
    }

    @Test
    void gymSwitchOffHandlesEverySubscriber() {
        Subscription first = subscription(EntryMode.HOME_GYM, "ACTIVE");
        first.setSubscriptionId(5L);
        Subscription second = subscription(EntryMode.HOME_GYM, "ACTIVE");
        second.setSubscriptionId(6L);
        second.setUserId(10L);
        SubscriptionHomeGym firstRow = activeRow(100L);
        firstRow.setSubscriptionId(5L);
        SubscriptionHomeGym secondRow = activeRow(100L);
        secondRow.setSubscriptionId(6L);
        secondRow.setUserId(10L);

        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of(5L, 6L));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(first));
        when(subscriptionRepository.findById(6L)).thenReturn(Optional.of(second));
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(firstRow));
        when(homeGymRepository.findActiveForUpdate(6L)).thenReturn(Optional.of(secondRow));

        service.onGymHomeDisabled(100L, null, null, null, null);

        assertEquals(HomeGymEndReason.GYM_HOME_DISABLED, firstRow.getEndReason());
        assertEquals(HomeGymEndReason.GYM_HOME_DISABLED, secondRow.getEndReason());
        verify(homeGymRepository).save(firstRow);
        verify(homeGymRepository).save(secondRow);
        verify(eventPublisher, times(2)).publishSubscriptionEvent(anyLong(), eq("HOME_GYM_DISABLED_BY_GYM"),
                anyLong(), any(SubscriptionEvent.class));
    }

    // ------------------------------------------------------------------
    // onRefundForUser fan-out
    // ------------------------------------------------------------------

    @Test
    void refundForUserIsANoOpWithoutAUser() {
        service.onRefundForUser(null);

        verifyNoInteractions(subscriptionRepository, settlementService);
    }

    @Test
    void refundForUserRefundsEverySubscriptionAndResettlesEach() {
        Subscription first = subscription(EntryMode.HOME_GYM, "ACTIVE");
        first.setSubscriptionId(5L);
        Subscription second = subscription(EntryMode.HOME_GYM, "ACTIVE");
        second.setSubscriptionId(6L);
        SubscriptionHomeGym row = activeRow(100L);
        row.setSubscriptionId(6L);

        when(subscriptionRepository.findByUserId(9L)).thenReturn(List.of(first, second));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(first));
        when(subscriptionRepository.findById(6L)).thenReturn(Optional.of(second));
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.empty());
        when(homeGymRepository.findActiveForUpdate(6L)).thenReturn(Optional.of(row));

        service.onRefundForUser(9L);

        assertEquals(NOW, first.getRefundedAt());
        assertEquals(NOW, second.getRefundedAt());
        // No active home-gym row for the first subscription: only the refund marker is written.
        assertEquals(EntryMode.HOME_GYM, first.getEntryMode());
        assertEquals(EntryMode.STANDARD, second.getEntryMode());
        assertEquals(HomeGymStatus.ENDED, row.getStatus());
        assertEquals(HomeGymEndReason.REFUND, row.getEndReason());
        verify(settlementService).resettleSubscription(5L);
        verify(settlementService).resettleSubscription(6L);
        verify(eventPublisher).publishSubscriptionEvent(eq(9L), eq("HOME_GYM_ENDED"), eq(6L),
                any(SubscriptionEvent.class));
    }

    @Test
    void refundForUserSkipsSubscriptionsThatDisappearedBetweenQueries() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "ACTIVE");
        when(subscriptionRepository.findByUserId(9L)).thenReturn(List.of(subscription));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.empty());

        service.onRefundForUser(9L);

        verify(settlementService, never()).resettleSubscription(anyLong());
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    // ------------------------------------------------------------------
    // Admin subscriber listing
    // ------------------------------------------------------------------

    @Test
    void gymSubscribersExposeTheActiveSelection() {
        Subscription subscription = subscription(EntryMode.HOME_GYM, "ACTIVE");
        SubscriptionHomeGym row = activeRow(100L);
        row.setEndsAt(PERIOD_ENDS);
        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of(5L));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                5L, HomeGymStatus.ACTIVE)).thenReturn(Optional.of(row));

        List<Map<String, Object>> subscribers = service.getGymSubscribers(100L);

        assertEquals(1, subscribers.size());
        Map<String, Object> item = subscribers.get(0);
        assertEquals(9L, item.get("userId"));
        assertEquals(5L, item.get("subscriptionId"));
        assertEquals(100L, item.get("gymId"));
        assertEquals(LocalDateTime.of(2026, 10, 6, 10, 0).toString(), item.get("selectedAt"));
        assertEquals(PERIOD_ENDS.toString(), item.get("endsAt"));
        assertEquals("SUBSCRIPTION_SCREEN", item.get("source"));
    }

    @Test
    void gymSubscribersTolerateAMissingActiveRowAndMissingSubscription() {
        Subscription subscription = subscription(EntryMode.STANDARD, "ACTIVE");
        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of(5L, 6L));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(subscriptionRepository.findById(6L)).thenReturn(Optional.empty());
        when(homeGymRepository.findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                5L, HomeGymStatus.ACTIVE)).thenReturn(Optional.empty());

        List<Map<String, Object>> subscribers = service.getGymSubscribers(100L);

        assertEquals(1, subscribers.size());
        Map<String, Object> item = subscribers.get(0);
        assertEquals(5L, item.get("subscriptionId"));
        assertNull(item.get("selectedAt"));
        assertNull(item.get("endsAt"));
        assertNull(item.get("source"));
    }

    @Test
    void gymSubscribersOfAnEmptyGymIsAnEmptyList() {
        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of());

        assertTrue(service.getGymSubscribers(100L).isEmpty());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private SubscriptionEvent captureSingleEvent() {
        ArgumentCaptor<SubscriptionEvent> captor = ArgumentCaptor.forClass(SubscriptionEvent.class);
        verify(eventPublisher).publishSubscriptionEvent(anyLong(), any(), anyLong(), captor.capture());
        return captor.getValue();
    }

    private static Subscription subscription(EntryMode mode, String status) {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(5L);
        subscription.setUserId(9L);
        subscription.setPackageId(3L);
        subscription.setStatus(status);
        subscription.setEntryMode(mode);
        subscription.setStartAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        subscription.setEndAt(LocalDateTime.of(2026, 12, 5, 9, 0));
        return subscription;
    }

    private static SubscriptionHomeGym activeRow(Long gymId) {
        return SubscriptionHomeGym.builder()
                .id(1L)
                .subscriptionId(5L)
                .userId(9L)
                .gymId(gymId)
                .status(HomeGymStatus.ACTIVE)
                .source(HomeGymSource.SUBSCRIPTION_SCREEN)
                .selectedAt(LocalDateTime.of(2026, 10, 6, 10, 0))
                .build();
    }
}
