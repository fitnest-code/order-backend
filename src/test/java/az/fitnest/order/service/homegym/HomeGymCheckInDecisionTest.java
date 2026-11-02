package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.config.HomeGymLimits;
import az.fitnest.order.dto.homegym.HomeGymCheckInContext;
import az.fitnest.order.dto.homegym.HomeGymCheckInResult;
import az.fitnest.order.event.SubscriptionEvent;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.HomeGymConfirmationToken;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.enums.CheckInDecision;
import az.fitnest.order.model.enums.ConfirmationType;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.HomeGymEndReason;
import az.fitnest.order.model.enums.HomeGymSource;
import az.fitnest.order.model.enums.HomeGymStatus;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.HomeGymConfirmationTokenRepository;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QR decision engine matrix (R3, R5-R8, R14-R18, R21): home-gym entry, standard entry,
 * the two home-gym gates, the other-gym lock, the switch confirmation token and the
 * frozen-subscription paths.
 */
@ExtendWith(MockitoExtension.class)
class HomeGymCheckInDecisionTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 5);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 10, 20, 0, 0);
    private static final LocalDateTime NEXT_DAY = LocalDateTime.of(2026, 10, 21, 0, 0);
    private static final LocalDateTime PERIOD_ENDS = LocalDateTime.of(2026, 11, 5, 0, 0);
    private static final String SWITCH = "SWITCH_TO_STANDARD";
    private static final String TOKEN = "c-token-1";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionHomeGymRepository homeGymRepository;
    @Mock private HomeGymConfirmationTokenRepository confirmationTokenRepository;
    @Mock private GymVisitRepository gymVisitRepository;
    @Mock private CatalogServiceGrpcClient catalogClient;
    @Mock private HomeGymPeriodCalculator periodCalculator;
    @Mock private SubscriptionEventPublisher eventPublisher;
    @Mock private HomeGymLimits homeGymLimits;

    @InjectMocks private HomeGymCheckInService service;

    @BeforeEach
    void baseStubs() {
        lenient().when(homeGymLimits.snapshot()).thenReturn(new HomeGymLimits.Snapshot(30, 1, 12, 12));
        lenient().when(periodCalculator.now()).thenReturn(NOW);
        lenient().when(periodCalculator.dayStart(NOW)).thenReturn(DAY_START);
        lenient().when(periodCalculator.nextDayStart(NOW)).thenReturn(NEXT_DAY);
        lenient().when(periodCalculator.periodStart(any(LocalDateTime.class), any(LocalDate.class)))
                .thenReturn(PERIOD_START);
        lenient().when(periodCalculator.periodEndsAt(nullable(LocalDateTime.class), any(LocalDate.class)))
                .thenReturn(PERIOD_ENDS);
        lenient().when(periodCalculator.periodEndsAt(nullable(LocalDateTime.class))).thenReturn(PERIOD_ENDS);
        lenient().when(periodCalculator.toOffset(nullable(LocalDateTime.class)))
                .thenAnswer(invocation -> offset(invocation.getArgument(0)));
        lenient().when(gymVisitRepository.save(any(GymVisit.class))).thenAnswer(invocation -> {
            GymVisit visit = invocation.getArgument(0);
            if (visit.getId() == null) {
                visit.setId(55L);
            }
            return visit;
        });
        lenient().when(confirmationTokenRepository.save(any(HomeGymConfirmationToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------
    // Another gym while the home gym is active (R5 / R6)
    // ------------------------------------------------------------------

    @Test
    void anotherGymWhileHomeGymIsActiveRequiresConfirmation() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        givenLockedSubscription(subscription);
        SubscriptionHomeGym active = activeHomeGym();
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(active));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(3L);
        when(catalogClient.getHomeGymInfo(100L, 3L)).thenReturn(homeGymInfo("FitZone"));

        HomeGymCheckInResult result = service.checkIn(context(200L, null, null));

        assertFalse(result.success());
        assertEquals(CheckInDecision.CONFIRMATION_REQUIRED, result.decision());
        assertEquals("home_gym.switch_warning", result.messageKey());
        assertEquals("SWITCH_TO_STANDARD", result.confirmationType());
        assertNotNull(result.confirmationToken());
        assertEquals(offset(NOW.plusMinutes(5)), result.confirmationExpiresAt());
        assertEquals("FitZone", result.homeGymName());
        assertEquals(100L, result.homeGymId());
        assertEquals(200L, result.targetGymId());
        assertEquals(3, result.homeVisitsThisPeriod());
        verify(confirmationTokenRepository).save(any(HomeGymConfirmationToken.class));
        verify(gymVisitRepository, never()).save(any(GymVisit.class));
    }

    @Test
    void confirmedSwitchEndsTheHomeGymAndStartsStandardMode() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        subscription.setRemainingLimit(10);
        givenLockedSubscription(subscription);
        SubscriptionHomeGym active = activeHomeGym();
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(active));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(3L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED)).thenReturn(3L);
        when(catalogClient.getHomeGymInfo(100L, 3L)).thenReturn(homeGymInfo("FitZone"));
        HomeGymConfirmationToken token = token(NOW.plusMinutes(4), NOW.minusMinutes(1));
        when(confirmationTokenRepository.findByTokenAndUsedAtIsNull(TOKEN)).thenReturn(Optional.of(token));
        when(gymVisitRepository.findFirstBySubscriptionIdAndGymIdAndEntryTypeOrderByCheckedInAtDesc(
                5L, 100L, VisitEntryType.HOME_UNLIMITED)).thenReturn(Optional.empty());

        HomeGymCheckInResult result = service.checkIn(context(200L, SWITCH, TOKEN));

        assertTrue(result.success());
        assertEquals(CheckInDecision.ENTERED_STANDARD, result.decision());
        assertEquals(VisitEntryType.STANDARD, result.entryType());
        assertEquals(55L, result.visitId());
        assertNull(result.homeGymId());
        // R5: the three home visits of the month start counting against the standard limit.
        assertEquals(4, result.standardVisitsUsed());

        assertEquals(HomeGymStatus.REMOVED_BY_SWITCH, active.getStatus());
        assertEquals(HomeGymEndReason.SWITCH, active.getEndReason());
        assertEquals(NOW, active.getEndedAt());
        assertEquals(55L, active.getEndReasonVisitId());
        assertEquals(EntryMode.STANDARD, subscription.getEntryMode());
        assertEquals(9, subscription.getRemainingLimit());
        assertEquals(NOW, token.getUsedAt());
        verify(eventPublisher).publishSubscriptionEvent(eq(9L), eq("HOME_GYM_REMOVED_BY_SWITCH"),
                eq(5L), any(SubscriptionEvent.class));
    }

    @Test
    void expiredConfirmationTokenIsBurnedAndAnewOneIsIssued() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));
        when(catalogClient.getHomeGymInfo(100L, 3L)).thenReturn(homeGymInfo("FitZone"));
        HomeGymConfirmationToken expired = token(NOW.minusMinutes(1), NOW.minusMinutes(6));
        when(confirmationTokenRepository.findByTokenAndUsedAtIsNull(TOKEN)).thenReturn(Optional.of(expired));

        HomeGymCheckInResult result = service.checkIn(context(200L, SWITCH, TOKEN));

        assertEquals(CheckInDecision.CONFIRMATION_REQUIRED, result.decision());
        assertEquals(NOW, expired.getUsedAt());
        // Burned token + freshly issued token.
        verify(confirmationTokenRepository, times(2)).save(any(HomeGymConfirmationToken.class));
        verify(gymVisitRepository, never()).save(any(GymVisit.class));
    }

    @Test
    void homeVisitAfterTheWarningInvalidatesTheToken() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));
        when(catalogClient.getHomeGymInfo(100L, 3L)).thenReturn(homeGymInfo("FitZone"));
        HomeGymConfirmationToken token = token(NOW.plusMinutes(4), NOW.minusMinutes(10));
        when(confirmationTokenRepository.findByTokenAndUsedAtIsNull(TOKEN)).thenReturn(Optional.of(token));
        when(gymVisitRepository.findFirstBySubscriptionIdAndGymIdAndEntryTypeOrderByCheckedInAtDesc(
                5L, 100L, VisitEntryType.HOME_UNLIMITED))
                .thenReturn(Optional.of(visit(70L, 100L, VisitEntryType.HOME_UNLIMITED, NOW.minusMinutes(5))));

        HomeGymCheckInResult result = service.checkIn(context(200L, SWITCH, TOKEN));

        assertEquals(CheckInDecision.CONFIRMATION_REQUIRED, result.decision());
        assertEquals(NOW, token.getUsedAt());
        verify(gymVisitRepository, never()).save(any(GymVisit.class));
    }

    @Test
    void tokenIssuedForAnotherGymIsRejected() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));
        when(catalogClient.getHomeGymInfo(100L, 3L)).thenReturn(homeGymInfo("FitZone"));
        HomeGymConfirmationToken token = token(NOW.plusMinutes(4), NOW.minusMinutes(1));
        token.setTargetGymId(300L);
        when(confirmationTokenRepository.findByTokenAndUsedAtIsNull(TOKEN)).thenReturn(Optional.of(token));

        HomeGymCheckInResult result = service.checkIn(context(200L, SWITCH, TOKEN));

        assertEquals(CheckInDecision.CONFIRMATION_REQUIRED, result.decision());
        assertEquals(NOW, token.getUsedAt());
    }

    // ------------------------------------------------------------------
    // R21: home-gym daily and monthly gates
    // ------------------------------------------------------------------

    @Test
    void secondHomeVisitOfTheDayHitsTheDailyGate() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(5L);
        when(gymVisitRepository.countCountableBySubscriptionAndGymAndTypeBetween(
                5L, 100L, VisitEntryType.HOME_UNLIMITED, DAY_START, NEXT_DAY)).thenReturn(1L);

        HomeGymCheckInResult result = service.checkIn(context(100L, null, null));

        assertFalse(result.success());
        assertEquals(CheckInDecision.DAILY_LIMIT_REACHED, result.decision());
        assertEquals("home_gym.daily_limit", result.messageKey());
        assertEquals(offset(NEXT_DAY), result.nextHomeEntryAt());
        assertEquals(5, result.homeVisitsThisPeriod());
        verify(gymVisitRepository, never()).save(any(GymVisit.class));
        verify(eventPublisher, never()).publishSubscriptionEvent(anyLong(), any(), anyLong());
    }

    @Test
    void thirtyFirstHomeVisitOfTheMonthHitsTheMonthlyGate() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(30L);
        when(gymVisitRepository.countCountableBySubscriptionAndGymAndTypeBetween(
                5L, 100L, VisitEntryType.HOME_UNLIMITED, DAY_START, NEXT_DAY)).thenReturn(0L);

        HomeGymCheckInResult result = service.checkIn(context(100L, null, null));

        assertFalse(result.success());
        assertEquals(CheckInDecision.HOME_MONTHLY_LIMIT_REACHED, result.decision());
        assertEquals("home_gym.monthly_limit", result.messageKey());
        assertEquals(offset(PERIOD_ENDS), result.renewsAt());
        assertEquals(30, result.homeVisitsThisPeriod());
        verify(gymVisitRepository, never()).save(any(GymVisit.class));
    }

    @Test
    void homeGymEntryIsUnlimitedAndPublishesCheckIn() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(5L);
        when(gymVisitRepository.countCountableBySubscriptionAndGymAndTypeBetween(
                5L, 100L, VisitEntryType.HOME_UNLIMITED, DAY_START, NEXT_DAY)).thenReturn(0L);

        HomeGymCheckInResult result = service.checkIn(context(100L, null, null));

        assertTrue(result.success());
        assertEquals(CheckInDecision.ENTERED_HOME_UNLIMITED, result.decision());
        assertEquals("home_gym.entered_home_unlimited", result.messageKey());
        assertEquals(VisitEntryType.HOME_UNLIMITED, result.entryType());
        assertEquals(100L, result.homeGymId());
        assertEquals(6, result.homeVisitsThisPeriod());
        assertNull(result.nextHomeEntryAt());
        verify(eventPublisher).publishSubscriptionEvent(9L, "CHECKIN", 5L);
        verify(gymVisitRepository).save(any(GymVisit.class));
    }

    // ------------------------------------------------------------------
    // Other gyms and the standard limit (R5-R7)
    // ------------------------------------------------------------------

    @Test
    void otherGymsAreLockedAfterTwelveHomeVisits() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(12L);

        HomeGymCheckInResult result = service.checkIn(context(200L, null, null));

        assertFalse(result.success());
        assertEquals(CheckInDecision.OTHER_GYMS_LOCKED, result.decision());
        assertEquals("home_gym.other_gyms_locked", result.messageKey());
        assertEquals(100L, result.homeGymId());
        assertEquals(200L, result.targetGymId());
        assertEquals(offset(PERIOD_ENDS), result.unlocksAt());
        verify(confirmationTokenRepository, never()).save(any(HomeGymConfirmationToken.class));
    }

    @Test
    void standardLimitIsSharedByHomeAndStandardVisits() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(5L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.STANDARD)).thenReturn(7L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED)).thenReturn(5L);

        HomeGymCheckInResult result = service.checkIn(context(200L, null, null));

        assertFalse(result.success());
        assertEquals(CheckInDecision.VISIT_LIMIT_EXCEEDED, result.decision());
        assertEquals("qr.limit_reached", result.messageKey());
        assertEquals(offset(PERIOD_ENDS), result.renewsAt());
        verify(gymVisitRepository, never()).save(any(GymVisit.class));
    }

    @Test
    void firstQrOfferIsShownOnlyWithoutAnyHomeGymHistory() {
        Subscription subscription = subscription(EntryMode.HOME_GYM_PENDING);
        givenLockedSubscription(subscription);
        when(homeGymRepository.existsBySubscriptionId(5L)).thenReturn(false);

        HomeGymCheckInResult result = service.checkIn(context(300L, null, null));

        assertEquals(CheckInDecision.ENTERED_OFFER_HOME_GYM, result.decision());
        assertEquals("home_gym.first_qr_offer", result.messageKey());
        assertEquals(VisitEntryType.STANDARD, result.entryType());
        assertNull(result.homeGymId());
    }

    @Test
    void standardEntryAfterHomeGymHistoryDoesNotOfferTheHomeGym() {
        Subscription subscription = subscription(EntryMode.HOME_GYM_PENDING);
        givenLockedSubscription(subscription);
        when(homeGymRepository.existsBySubscriptionId(5L)).thenReturn(true);

        HomeGymCheckInResult result = service.checkIn(context(300L, null, null));

        assertEquals(CheckInDecision.ENTERED_STANDARD, result.decision());
        assertEquals("home_gym.standard_mode.note", result.messageKey());
    }

    @Test
    void standardModeCountsHomeVisitsAgainstTheRemainingLimit() {
        Subscription subscription = subscription(EntryMode.STANDARD);
        subscription.setRemainingLimit(5);
        givenLockedSubscription(subscription);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.STANDARD)).thenReturn(2L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED)).thenReturn(1L);

        HomeGymCheckInResult result = service.checkIn(context(300L, null, null));

        assertEquals(CheckInDecision.ENTERED_STANDARD, result.decision());
        assertEquals(4, result.standardVisitsUsed());
        assertEquals(4, subscription.getRemainingLimit());
        assertEquals("ACTIVE", subscription.getStatus());
    }

    @Test
    void lastStandardUnitFinishesTheSubscription() {
        Subscription subscription = subscription(EntryMode.STANDARD);
        subscription.setRemainingLimit(1);
        subscription.setFrozenSessions(0);
        givenLockedSubscription(subscription);

        HomeGymCheckInResult result = service.checkIn(context(300L, null, null));

        assertEquals(CheckInDecision.ENTERED_STANDARD, result.decision());
        assertEquals(0, subscription.getRemainingLimit());
        assertEquals("FINISHED", subscription.getStatus());
    }

    // ------------------------------------------------------------------
    // Subscription selection and rejection paths
    // ------------------------------------------------------------------

    @Test
    void frozenSubscriptionIsRejected() {
        Subscription frozen = subscription(EntryMode.STANDARD);
        frozen.setStatus("FROZEN");
        when(subscriptionRepository.findByUserIdAndStatusIn(9L, List.of("ACTIVE", "FINISHED")))
                .thenReturn(List.of());
        when(subscriptionRepository.findByUserIdAndStatus(9L, "FROZEN")).thenReturn(List.of(frozen));
        when(subscriptionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(frozen));

        HomeGymCheckInResult result = service.checkIn(context(100L, null, null));

        assertFalse(result.success());
        assertEquals(CheckInDecision.SUBSCRIPTION_FROZEN, result.decision());
        assertEquals("error.subscription_frozen", result.messageKey());
    }

    @Test
    void expiredSubscriptionIsRejected() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        subscription.setEndAt(NOW.minusDays(1));
        givenLockedSubscription(subscription);

        HomeGymCheckInResult result = service.checkIn(context(100L, null, null));

        assertEquals(CheckInDecision.NO_ACTIVE_SUBSCRIPTION, result.decision());
        assertEquals("home_gym.no_active_subscription", result.messageKey());
    }

    @Test
    void missingSubscriptionIsRejected() {
        when(subscriptionRepository.findByUserIdAndStatusIn(9L, List.of("ACTIVE", "FINISHED")))
                .thenReturn(List.of());
        when(subscriptionRepository.findByUserIdAndStatus(9L, "FROZEN")).thenReturn(List.of());

        HomeGymCheckInResult result = service.checkIn(context(100L, null, null));

        assertEquals(CheckInDecision.NO_ACTIVE_SUBSCRIPTION, result.decision());
    }

    @Test
    void theLatestEndingSubscriptionIsLocked() {
        Subscription shortOne = subscription(EntryMode.HOME_GYM);
        shortOne.setSubscriptionId(4L);
        shortOne.setEndAt(NOW.plusDays(3));
        Subscription longOne = subscription(EntryMode.HOME_GYM);
        longOne.setSubscriptionId(6L);
        longOne.setEndAt(NOW.plusDays(30));
        when(subscriptionRepository.findByUserIdAndStatusIn(9L, List.of("ACTIVE", "FINISHED")))
                .thenReturn(List.of(shortOne, longOne));
        when(subscriptionRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(longOne));
        when(homeGymRepository.findActiveForUpdate(6L)).thenReturn(Optional.of(activeHomeGym()));
        when(periodCalculator.periodStart(any(LocalDateTime.class), any(LocalDate.class)))
                .thenReturn(PERIOD_START);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                6L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(0L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                6L, PERIOD_START, VisitEntryType.STANDARD)).thenReturn(0L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                6L, PERIOD_START, VisitEntryType.HOME_UNLIMITED)).thenReturn(0L);
        when(gymVisitRepository.countCountableBySubscriptionAndGymAndTypeBetween(
                6L, 100L, VisitEntryType.HOME_UNLIMITED, DAY_START, NEXT_DAY)).thenReturn(0L);

        HomeGymCheckInResult result = service.checkIn(context(100L, null, null));

        assertTrue(result.success());
        verify(subscriptionRepository).findByIdForUpdate(6L);
    }

    // ------------------------------------------------------------------
    // Frozen reservations (R10)
    // ------------------------------------------------------------------

    @Test
    void frozenSessionAtTheHomeGymDoesNotConsumeTheLimit() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        subscription.setRemainingLimit(7);
        subscription.setFrozenSessions(2);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));

        HomeGymCheckInResult result = service.checkIn(
                new HomeGymCheckInContext(9L, 100L, true, null, null, null, null, null, null, null));

        assertTrue(result.success());
        assertEquals(CheckInDecision.ENTERED_HOME_UNLIMITED, result.decision());
        assertEquals(1, subscription.getFrozenSessions());
        assertEquals(7, subscription.getRemainingLimit());
        verify(subscriptionRepository).save(subscription);
        verify(eventPublisher).publishSubscriptionEvent(9L, "CHECKIN_FROZEN", 5L);
        verify(gymVisitRepository).save(any(GymVisit.class));
    }

    @Test
    void frozenSessionWithoutACreditIsRejected() {
        Subscription subscription = subscription(EntryMode.HOME_GYM);
        subscription.setFrozenSessions(0);
        givenLockedSubscription(subscription);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(activeHomeGym()));

        HomeGymCheckInContext context =
                new HomeGymCheckInContext(9L, 100L, true, null, null, null, null, null, null, null);

        assertThrows(BadRequestException.class, () -> service.checkIn(context));
        verify(gymVisitRepository, never()).save(any(GymVisit.class));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void givenLockedSubscription(Subscription subscription) {
        lenient().when(subscriptionRepository.findByUserIdAndStatusIn(9L, List.of("ACTIVE", "FINISHED")))
                .thenReturn(List.of(subscription));
        lenient().when(subscriptionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(subscription));
        lenient().when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.empty());
        lenient().when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.STANDARD)).thenReturn(0L);
        lenient().when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED)).thenReturn(0L);
        lenient().when(gymVisitRepository.countCountableBySubscriptionAndGymAndTypeBetween(
                5L, 100L, VisitEntryType.HOME_UNLIMITED, DAY_START, NEXT_DAY)).thenReturn(0L);
    }

    private static HomeGymCheckInContext context(Long gymId, String confirmation, String token) {
        return new HomeGymCheckInContext(9L, gymId, false, confirmation, token, null, null, null, null, null);
    }

    private static Subscription subscription(EntryMode mode) {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(5L);
        subscription.setUserId(9L);
        subscription.setPackageId(3L);
        subscription.setStatus("ACTIVE");
        subscription.setEntryMode(mode);
        subscription.setStartAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        subscription.setEndAt(LocalDateTime.of(2026, 12, 5, 9, 0));
        subscription.setTotalLimit(12);
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

    private static HomeGymConfirmationToken token(LocalDateTime expiresAt, LocalDateTime createdAt) {
        return HomeGymConfirmationToken.builder()
                .id(11L)
                .token(TOKEN)
                .userId(9L)
                .subscriptionId(5L)
                .targetGymId(200L)
                .confirmationType(ConfirmationType.SWITCH_TO_STANDARD)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .build();
    }

    private static GymVisit visit(Long id, Long gymId, VisitEntryType type, LocalDateTime checkedInAt) {
        return GymVisit.builder()
                .id(id)
                .gymId(gymId)
                .entryType(type)
                .checkedInAt(checkedInAt)
                .build();
    }

    private static CatalogServiceGrpcClient.HomeGymInfo homeGymInfo(String name) {
        return new CatalogServiceGrpcClient.HomeGymInfo(true, name, true, true, null);
    }

    /** Matches the SubscriptionEvent published on a switch (only userId/action/id matter). */
    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(HomeGymPeriodCalculator.BAKU).toOffsetDateTime();
    }
}
