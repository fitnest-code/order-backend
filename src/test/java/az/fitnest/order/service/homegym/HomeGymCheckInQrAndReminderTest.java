package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.dto.homegym.HomeGymCheckInContext;
import az.fitnest.order.dto.homegym.HomeGymCheckInResult;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.enums.CheckInDecision;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.HomeGymConfirmationTokenRepository;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T-16 qrScanId contract and the 24h reminder quiet-window rules (spec push table).
 */
@ExtendWith(MockitoExtension.class)
class HomeGymCheckInQrAndReminderTest {

    private static final String SCAN_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionHomeGymRepository homeGymRepository;
    @Mock private HomeGymConfirmationTokenRepository confirmationTokenRepository;
    @Mock private GymVisitRepository gymVisitRepository;
    @Mock private CatalogServiceGrpcClient catalogClient;
    @Mock private HomeGymPeriodCalculator periodCalculator;
    @Mock private SubscriptionEventPublisher eventPublisher;

    @InjectMocks private HomeGymCheckInService service;

    @Test
    void invalidQrScanIdIsRejectedBeforeTouchingTheSubscription() {
        HomeGymCheckInResult result = service.checkIn(context(100L, "not-a-uuid"));

        assertFalse(result.success());
        assertEquals(CheckInDecision.INVALID_QR_SCAN_ID, result.decision());
        assertEquals("home_gym.invalid_qr_scan_id", result.messageKey());
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    void replayedScanIdForTheSameGymReturnsTheFirstVisitWithoutWritingANewOne() {
        Subscription subscription = subscription();
        GymVisit first = visit(77L, 100L, VisitEntryType.STANDARD);
        stubLockedSubscription(subscription);
        when(gymVisitRepository.findFirstBySubscriptionIdAndQrScanIdOrderByCheckedInAtDesc(5L, SCAN_ID))
                .thenReturn(Optional.of(first));

        HomeGymCheckInResult result = service.checkIn(context(100L, SCAN_ID));

        assertTrue(result.success());
        assertEquals(CheckInDecision.ENTERED_STANDARD, result.decision());
        assertEquals(77L, result.visitId());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void sameScanIdForAnotherGymIsAConflict() {
        Subscription subscription = subscription();
        GymVisit first = visit(77L, 200L, VisitEntryType.STANDARD);
        stubLockedSubscription(subscription);
        when(gymVisitRepository.findFirstBySubscriptionIdAndQrScanIdOrderByCheckedInAtDesc(5L, SCAN_ID))
                .thenReturn(Optional.of(first));

        HomeGymCheckInResult result = service.checkIn(context(100L, SCAN_ID));

        assertFalse(result.success());
        assertEquals(CheckInDecision.QR_SCAN_CONFLICT, result.decision());
        assertEquals("home_gym.qr_scan_conflict", result.messageKey());
        assertNull(result.visitId());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void canonicalUuidCheckAcceptsOnly36CharacterUuids() {
        assertTrue(HomeGymCheckInService.isCanonicalUuid(SCAN_ID));
        assertTrue(HomeGymCheckInService.isCanonicalUuid(SCAN_ID.toUpperCase()));
        assertFalse(HomeGymCheckInService.isCanonicalUuid(null));
        assertFalse(HomeGymCheckInService.isCanonicalUuid(""));
        assertFalse(HomeGymCheckInService.isCanonicalUuid("3f2504e0-4f89-41d3-9a0c-0305e82c330"));
        assertFalse(HomeGymCheckInService.isCanonicalUuid("zzzzzzzz-4f89-41d3-9a0c-0305e82c3301"));
    }

    @Test
    void reminderAtIsShiftedOutOfTheQuietWindow() {
        // 22:00-23:59 -> next day 10:00
        assertEquals(LocalDateTime.of(2026, 11, 7, 10, 0),
                HomeGymReminderService.reminderAt(LocalDateTime.of(2026, 11, 5, 22, 0)));
        assertEquals(LocalDateTime.of(2026, 11, 7, 10, 0),
                HomeGymReminderService.reminderAt(LocalDateTime.of(2026, 11, 5, 23, 30)));
        // 00:00-08:59 -> same day 10:00
        assertEquals(LocalDateTime.of(2026, 11, 6, 10, 0),
                HomeGymReminderService.reminderAt(LocalDateTime.of(2026, 11, 5, 3, 0)));
        assertEquals(LocalDateTime.of(2026, 11, 6, 10, 0),
                HomeGymReminderService.reminderAt(LocalDateTime.of(2026, 11, 5, 8, 59)));
        // Outside the quiet window the exact +24h moment is kept.
        assertEquals(LocalDateTime.of(2026, 11, 6, 9, 0),
                HomeGymReminderService.reminderAt(LocalDateTime.of(2026, 11, 5, 9, 0)));
        assertEquals(LocalDateTime.of(2026, 11, 6, 21, 59),
                HomeGymReminderService.reminderAt(LocalDateTime.of(2026, 11, 5, 21, 59)));
    }

    @Test
    void quietHourCoversTwentyTwoToNine() {
        assertFalse(HomeGymReminderService.isQuietHour(java.time.LocalTime.of(21, 59)));
        assertTrue(HomeGymReminderService.isQuietHour(java.time.LocalTime.of(22, 0)));
        assertTrue(HomeGymReminderService.isQuietHour(java.time.LocalTime.of(23, 59)));
        assertTrue(HomeGymReminderService.isQuietHour(java.time.LocalTime.of(0, 0)));
        assertTrue(HomeGymReminderService.isQuietHour(java.time.LocalTime.of(8, 59)));
        assertFalse(HomeGymReminderService.isQuietHour(java.time.LocalTime.of(9, 0)));
    }

    @Test
    void reminderJobSendsNothingDuringQuietHours() {
        when(periodCalculator.now()).thenReturn(LocalDateTime.of(2026, 10, 27, 23, 30));
        HomeGymReminderService reminderService = new HomeGymReminderService(
                subscriptionRepository, homeGymRepository, catalogClient, periodCalculator, eventPublisher);

        reminderService.sendDueReminders();

        verifyNoInteractions(subscriptionRepository);
        verifyNoInteractions(eventPublisher);
    }

    private void stubLockedSubscription(Subscription subscription) {
        when(periodCalculator.now()).thenReturn(LocalDateTime.of(2026, 10, 27, 12, 0));
        when(periodCalculator.dayStart(any())).thenReturn(LocalDateTime.of(2026, 10, 27, 0, 0));
        when(periodCalculator.nextDayStart(any())).thenReturn(LocalDateTime.of(2026, 10, 28, 0, 0));
        when(periodCalculator.periodStart(any(), any())).thenReturn(java.time.LocalDate.of(2026, 10, 5));
        when(subscriptionRepository.findByUserIdAndStatusIn(9L, List.of("ACTIVE", "FINISHED")))
                .thenReturn(List.of(subscription));
        when(subscriptionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.empty());
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, java.time.LocalDate.of(2026, 10, 5), VisitEntryType.STANDARD)).thenReturn(0L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, java.time.LocalDate.of(2026, 10, 5), VisitEntryType.HOME_UNLIMITED)).thenReturn(0L);
    }

    private HomeGymCheckInContext context(Long gymId, String qrScanId) {
        return new HomeGymCheckInContext(9L, gymId, false, null, null, null, null,
                null, null, qrScanId);
    }

    private Subscription subscription() {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(5L);
        subscription.setUserId(9L);
        subscription.setPackageId(3L);
        subscription.setStatus("ACTIVE");
        subscription.setEntryMode(EntryMode.HOME_GYM);
        subscription.setStartAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        subscription.setEndAt(LocalDateTime.of(2026, 11, 5, 9, 0));
        subscription.setTotalLimit(12);
        return subscription;
    }

    private GymVisit visit(Long id, Long gymId, VisitEntryType entryType) {
        GymVisit visit = new GymVisit();
        visit.setId(id);
        visit.setGymId(gymId);
        visit.setEntryType(entryType);
        return visit;
    }
}
