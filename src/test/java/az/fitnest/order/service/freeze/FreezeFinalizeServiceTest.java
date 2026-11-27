package az.fitnest.order.service.freeze;

import az.fitnest.order.model.entity.FreezeAuditEvent;
import az.fitnest.order.model.entity.FreezeOutboxEvent;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionFreeze;
import az.fitnest.order.model.entity.SubscriptionFreezeEntitlement;
import az.fitnest.order.model.enums.FreezeStatus;
import az.fitnest.order.repository.FreezeAuditEventRepository;
import az.fitnest.order.repository.FreezeOutboxEventRepository;
import az.fitnest.order.repository.SubscriptionFreezeEntitlementRepository;
import az.fitnest.order.repository.SubscriptionFreezeRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreezeFinalizeServiceTest {

    private static final Long SUBSCRIPTION_ID = 11L;
    private static final Long USER_ID = 22L;
    private static final Long FREEZE_ID = 33L;
    private static final long DAY_SECONDS = 86_400L;

    @Mock private SubscriptionFreezeRepository freezeRepository;
    @Mock private SubscriptionFreezeEntitlementRepository entitlementRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionPackageRepository packageRepository;
    @Mock private FreezeAuditEventRepository auditEventRepository;
    @Mock private FreezeOutboxEventRepository outboxEventRepository;
    @Mock private FreezeEntitlementService entitlementService;

    private FreezeFinalizeService service;
    private FreezeFinalizeService serviceWithRealEntitlement;

    @BeforeEach
    void setUp() {
        service = new FreezeFinalizeService(
                freezeRepository, entitlementRepository, subscriptionRepository,
                auditEventRepository, outboxEventRepository, entitlementService);

        FreezeEntitlementService realEntitlementService = new FreezeEntitlementService(
                entitlementRepository, new FreezeTierPolicyProvider(), subscriptionRepository, packageRepository);
        serviceWithRealEntitlement = new FreezeFinalizeService(
                freezeRepository, entitlementRepository, subscriptionRepository,
                auditEventRepository, outboxEventRepository, realEntitlementService);
    }

    @Test
    void completeMarksFreezeCompletedAndDelegatesEntitlementFinalisation() {
        LocalDateTime startAt = YearMonth.now().atDay(1).atStartOfDay().plusDays(2);
        SubscriptionFreeze freeze = freeze(FreezeStatus.ACTIVE, 5, startAt);
        freeze.setPlanEndAt(LocalDateTime.now().minusDays(1));
        Subscription sub = subscription();
        LocalDateTime paidUntilBefore = sub.getPaidUntil();

        when(freezeRepository.findByIdForUpdate(FREEZE_ID)).thenReturn(Optional.of(freeze));
        when(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));

        service.complete(freeze);

        assertEquals(FreezeStatus.COMPLETED, freeze.getStatus());
        assertEquals(5, freeze.getUsedDays());
        assertEquals(0, freeze.getReturnedDays());
        assertEquals("WORKER", freeze.getEndedBy());
        assertEquals(freeze.getPlanEndAt(), freeze.getActualEndAt());
        assertEquals(freeze.getExpiryBefore().plusSeconds(5 * DAY_SECONDS), freeze.getExpiryAfter());
        assertEquals("ACTIVE", sub.getStatus());
        assertEquals(freeze.getExpiryAfter(), sub.getEndAt());
        assertEquals(paidUntilBefore.plusSeconds(5 * DAY_SECONDS), sub.getPaidUntil());
        assertEquals(7, sub.getFrozenDaysUsed());
        assertNull(sub.getFrozenAt());
        assertNull(sub.getUnfreezesAt());

        verify(entitlementService).finaliseConsumed(SUBSCRIPTION_ID, 5, 0, startAt);
        ArgumentCaptor<FreezeAuditEvent> audit = ArgumentCaptor.forClass(FreezeAuditEvent.class);
        verify(auditEventRepository).save(audit.capture());
        assertEquals("FREEZE_COMPLETED", audit.getValue().getEventType());
        ArgumentCaptor<FreezeOutboxEvent> outbox = ArgumentCaptor.forClass(FreezeOutboxEvent.class);
        verify(outboxEventRepository).save(outbox.capture());
        assertEquals("freeze_completed", outbox.getValue().getEventType());
    }

    @Test
    void completeStartedInPreviousMonthReleasesReservationWithoutChargingCurrentMonth() {
        LocalDateTime startAt = YearMonth.now().minusMonths(1).atDay(10).atStartOfDay();
        SubscriptionFreeze freeze = freeze(FreezeStatus.ACTIVE, 5, startAt);
        freeze.setPlanEndAt(LocalDateTime.now().minusDays(1));
        SubscriptionFreezeEntitlement entitlement = entitlement(10, 0, 5, YearMonth.now().toString());

        when(freezeRepository.findByIdForUpdate(FREEZE_ID)).thenReturn(Optional.of(freeze));
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));
        when(entitlementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        serviceWithRealEntitlement.complete(freeze);

        assertEquals(0, entitlement.getConsumedDays());
        assertEquals(0, entitlement.getReservedDays());
        assertEquals(10, entitlement.getAvailableDays());
        assertEquals(FreezeStatus.COMPLETED, freeze.getStatus());
    }

    @Test
    void completeIgnoresFreezeThatIsAlreadyNotActive() {
        SubscriptionFreeze freeze = freeze(FreezeStatus.COMPLETED, 5, LocalDateTime.now().minusDays(2));

        service.complete(freeze);

        verifyNoInteractions(freezeRepository, entitlementService, subscriptionRepository,
                auditEventRepository, outboxEventRepository);
    }

    @Test
    void completeSkipsWhenLockedFreezeChangedStatus() {
        SubscriptionFreeze incoming = freeze(FreezeStatus.ACTIVE, 5, LocalDateTime.now().minusDays(2));
        SubscriptionFreeze locked = freeze(FreezeStatus.TERMINATED, 5, incoming.getStartAt());
        when(freezeRepository.findByIdForUpdate(FREEZE_ID)).thenReturn(Optional.of(locked));

        service.complete(incoming);

        verify(freezeRepository, never()).save(any());
        verifyNoInteractions(entitlementService, subscriptionRepository,
                auditEventRepository, outboxEventRepository);
    }

    @Test
    void terminateActiveReleasesReservationWithoutConsumingDays() {
        SubscriptionFreeze freeze = freeze(FreezeStatus.ACTIVE, 4, LocalDateTime.now().minusDays(1));
        SubscriptionFreezeEntitlement entitlement = entitlement(10, 1, 4, YearMonth.now().toString());
        Subscription sub = subscription();

        when(freezeRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, FreezeStatus.ACTIVE))
                .thenReturn(Optional.of(freeze));
        when(freezeRepository.findByIdForUpdate(FREEZE_ID)).thenReturn(Optional.of(freeze));
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));
        when(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));

        service.terminateActive(SUBSCRIPTION_ID, "ASSIGN");

        assertEquals(FreezeStatus.TERMINATED, freeze.getStatus());
        assertEquals("ASSIGN", freeze.getEndedBy());
        assertEquals(0, entitlement.getReservedDays());
        assertEquals(1, entitlement.getConsumedDays());
        assertNull(sub.getFrozenAt());
        assertNull(sub.getUnfreezesAt());
        verify(entitlementRepository).save(entitlement);
        verifyNoInteractions(entitlementService);

        ArgumentCaptor<FreezeAuditEvent> audit = ArgumentCaptor.forClass(FreezeAuditEvent.class);
        verify(auditEventRepository).save(audit.capture());
        assertEquals("FREEZE_TERMINATED", audit.getValue().getEventType());
        ArgumentCaptor<FreezeOutboxEvent> outbox = ArgumentCaptor.forClass(FreezeOutboxEvent.class);
        verify(outboxEventRepository).save(outbox.capture());
        assertEquals("freeze_blocked", outbox.getValue().getEventType());
    }

    @Test
    void terminateActiveIgnoresMissingFreeze() {
        when(freezeRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, FreezeStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.terminateActive(SUBSCRIPTION_ID, "SYSTEM");

        verify(freezeRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(entitlementRepository, entitlementService, subscriptionRepository,
                auditEventRepository, outboxEventRepository);
    }

    @Test
    void shiftSuccessorStartDatesOnlyMovesPendingSubscriptionsBeforeNewEnd() {
        LocalDateTime newEndAt = LocalDateTime.now().plusDays(5);
        Subscription overlapping = pendingSubscription(newEndAt.minusDays(1), newEndAt.plusDays(29));
        Subscription future = pendingSubscription(newEndAt.plusDays(1), newEndAt.plusDays(31));
        when(subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(USER_ID, "PENDING"))
                .thenReturn(List.of(overlapping, future));

        service.shiftSuccessorStartDates(USER_ID, newEndAt);

        assertEquals(newEndAt, overlapping.getStartAt());
        assertEquals(newEndAt.plusDays(30), overlapping.getEndAt());
        assertEquals(newEndAt.plusDays(1), future.getStartAt());
        verify(subscriptionRepository, times(1)).save(overlapping);
        verify(subscriptionRepository, never()).save(future);
    }

    private SubscriptionFreeze freeze(FreezeStatus status, int requestedDays, LocalDateTime startAt) {
        return SubscriptionFreeze.builder()
                .id(FREEZE_ID)
                .subscriptionId(SUBSCRIPTION_ID)
                .userId(USER_ID)
                .requestedDays(requestedDays)
                .startAt(startAt)
                .planEndAt(startAt.plusDays(requestedDays))
                .expiryBefore(startAt.plusDays(30))
                .status(status)
                .version(0L)
                .build();
    }

    private Subscription subscription() {
        LocalDateTime now = LocalDateTime.now();
        Subscription sub = new Subscription();
        sub.setSubscriptionId(SUBSCRIPTION_ID);
        sub.setUserId(USER_ID);
        sub.setStatus("FROZEN");
        sub.setStartAt(now.minusDays(30));
        sub.setEndAt(now.plusDays(30));
        sub.setPaidUntil(now.plusDays(30));
        sub.setFrozenAt(now.minusDays(1));
        sub.setUnfreezesAt(now.plusDays(4));
        sub.setFrozenDaysUsed(2);
        return sub;
    }

    private Subscription pendingSubscription(LocalDateTime startAt, LocalDateTime endAt) {
        Subscription sub = new Subscription();
        sub.setSubscriptionId(startAt.toLocalDate().toEpochDay());
        sub.setUserId(USER_ID);
        sub.setStatus("PENDING");
        sub.setStartAt(startAt);
        sub.setEndAt(endAt);
        return sub;
    }

    private SubscriptionFreezeEntitlement entitlement(
            int totalDays, int consumedDays, int reservedDays, String allowanceMonth) {
        return SubscriptionFreezeEntitlement.builder()
                .subscriptionId(SUBSCRIPTION_ID)
                .userId(USER_ID)
                .totalDays(totalDays)
                .consumedDays(consumedDays)
                .reservedDays(reservedDays)
                .policyVersion("v1.2")
                .allowanceMonth(allowanceMonth)
                .build();
    }
}
