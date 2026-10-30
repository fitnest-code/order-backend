package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.config.HomeGymLimits;
import az.fitnest.order.dto.homegym.HomeGymCheckInContext;
import az.fitnest.order.dto.homegym.HomeGymCheckInResult;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.enums.CheckInDecision;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.HomeGymStatus;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T-R4: the QR decision engine must run with the configured limits, not with hard-coded
 * constants, so a configuration change updates the rules text and the enforcement together.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HomeGymConfiguredLimitsTest {

    private static final Long USER_ID = 9L;
    private static final Long SUBSCRIPTION_ID = 5L;
    private static final Long GYM_ID = 100L;
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 5);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionHomeGymRepository homeGymRepository;
    @Mock private HomeGymConfirmationTokenRepository confirmationTokenRepository;
    @Mock private GymVisitRepository gymVisitRepository;
    @Mock private CatalogServiceGrpcClient catalogClient;
    @Mock private HomeGymPeriodCalculator periodCalculator;
    @Mock private SubscriptionEventPublisher eventPublisher;
    @Mock private HomeGymLimits homeGymLimits;

    @InjectMocks private HomeGymCheckInService service;

    @Test
    void homeGymMonthlyGateUsesTheConfiguredLimit() {
        HomeGymCheckInResult atFortyFour = checkInWith(44, 45);
        assertEquals(CheckInDecision.ENTERED_HOME_UNLIMITED, atFortyFour.decision());

        HomeGymCheckInResult atFortyFive = checkInWith(45, 45);
        assertEquals(CheckInDecision.HOME_MONTHLY_LIMIT_REACHED, atFortyFive.decision());
    }

    @Test
    void otherGymsLockThresholdUsesTheConfiguredLimit() {
        HomeGymCheckInResult locked = checkInAtAnotherGym(20, 30, 20);
        assertEquals(CheckInDecision.OTHER_GYMS_LOCKED, locked.decision());
    }

    private HomeGymCheckInResult checkInWith(long homeVisits, int monthlyLimit) {
        stubSubscription(homeVisits, monthlyLimit, 12);
        return service.checkIn(context(GYM_ID));
    }

    private HomeGymCheckInResult checkInAtAnotherGym(long homeVisits, int monthlyLimit, int lockThreshold) {
        stubSubscription(homeVisits, monthlyLimit, lockThreshold);
        return service.checkIn(context(200L));
    }

    private void stubSubscription(long homeVisits, int monthlyLimit, int lockThreshold) {
        when(homeGymLimits.snapshot())
                .thenReturn(new HomeGymLimits.Snapshot(monthlyLimit, 1, 12, lockThreshold));

        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(SUBSCRIPTION_ID);
        subscription.setUserId(USER_ID);
        subscription.setPackageId(3L);
        subscription.setStatus("ACTIVE");
        subscription.setEntryMode(EntryMode.HOME_GYM);
        subscription.setStartAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        subscription.setEndAt(LocalDateTime.of(2026, 11, 5, 9, 0));
        subscription.setTotalLimit(12);

        SubscriptionHomeGym active = SubscriptionHomeGym.builder()
                .id(1L)
                .subscriptionId(SUBSCRIPTION_ID)
                .userId(USER_ID)
                .gymId(GYM_ID)
                .status(HomeGymStatus.ACTIVE)
                .selectedAt(LocalDateTime.of(2026, 10, 5, 9, 0))
                .build();

        when(periodCalculator.now()).thenReturn(NOW);
        when(periodCalculator.dayStart(any())).thenReturn(LocalDateTime.of(2026, 10, 27, 0, 0));
        when(periodCalculator.nextDayStart(any())).thenReturn(LocalDateTime.of(2026, 10, 28, 0, 0));
        when(periodCalculator.periodStart(any(), any())).thenReturn(PERIOD_START);
        when(periodCalculator.periodEndsAt(any(LocalDateTime.class))).thenReturn(LocalDateTime.of(2026, 11, 5, 0, 0));

        when(subscriptionRepository.findByUserIdAndStatusIn(USER_ID, List.of("ACTIVE", "FINISHED")))
                .thenReturn(List.of(subscription));
        when(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findActiveForUpdate(SUBSCRIPTION_ID)).thenReturn(Optional.of(active));

        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                SUBSCRIPTION_ID, PERIOD_START, VisitEntryType.HOME_UNLIMITED, GYM_ID)).thenReturn(homeVisits);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                SUBSCRIPTION_ID, PERIOD_START, VisitEntryType.STANDARD)).thenReturn(0L);
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                SUBSCRIPTION_ID, PERIOD_START, VisitEntryType.HOME_UNLIMITED)).thenReturn(homeVisits);
        when(gymVisitRepository.countCountableBySubscriptionAndGymAndTypeBetween(
                SUBSCRIPTION_ID, GYM_ID, VisitEntryType.HOME_UNLIMITED,
                LocalDateTime.of(2026, 10, 27, 0, 0), LocalDateTime.of(2026, 10, 28, 0, 0))).thenReturn(0L);

        GymVisit saved = new GymVisit();
        saved.setId(99L);
        saved.setGymId(GYM_ID);
        saved.setEntryType(VisitEntryType.HOME_UNLIMITED);
        when(gymVisitRepository.save(any())).thenReturn(saved);
    }

    private HomeGymCheckInContext context(Long gymId) {
        return new HomeGymCheckInContext(USER_ID, gymId, false, null, null, null, null, null, null, null);
    }
}
