package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.config.HomeGymLimits;
import az.fitnest.order.dto.homegym.HomeGymResponse;
import az.fitnest.order.dto.homegym.HomeGymSelectRequest;
import az.fitnest.order.event.SubscriptionEvent;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.exception.HomeGymException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.HomeGymIdempotency;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.HomeGymEndReason;
import az.fitnest.order.model.enums.HomeGymSource;
import az.fitnest.order.model.enums.HomeGymStatus;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.HomeGymIdempotencyRepository;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Home-gym selection/change flow (R1-R4, R8, R11, R13, R19, R20): source validation,
 * idempotency, ownership, eligibility, the R19 visit conversion, the FIRST_QR promotion
 * and the upgrade hook.
 */
@ExtendWith(MockitoExtension.class)
class HomeGymSelectionFlowTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 5);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final LocalDateTime PERIOD_ENDS = LocalDateTime.of(2026, 11, 5, 0, 0);
    private static final HomeGymLimits.Snapshot LIMITS = new HomeGymLimits.Snapshot(30, 1, 12, 12);

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionHomeGymRepository homeGymRepository;
    @Mock private GymVisitRepository gymVisitRepository;
    @Mock private HomeGymIdempotencyRepository idempotencyRepository;
    @Mock private CatalogServiceGrpcClient catalogClient;
    @Mock private HomeGymPeriodCalculator periodCalculator;
    @Mock private HomeGymSelectionPolicy selectionPolicy;
    @Mock private SubscriptionEventPublisher eventPublisher;
    @Mock private ObjectMapper objectMapper;
    @Mock private SubscriptionPackageRepository packageRepository;
    @Mock private HomeGymLimits homeGymLimits;

    @InjectMocks private HomeGymService service;

    private final AtomicReference<SubscriptionHomeGym> activeRow = new AtomicReference<>();

    @BeforeEach
    void baseStubs() throws Exception {
        lenient().when(subscriptionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(subscription()));
        lenient().when(periodCalculator.now()).thenReturn(NOW);
        lenient().when(periodCalculator.periodStart(any(LocalDateTime.class), any(LocalDate.class)))
                .thenReturn(PERIOD_START);
        lenient().when(periodCalculator.periodEndsAt(any(LocalDateTime.class), any(LocalDate.class)))
                .thenReturn(PERIOD_ENDS);
        lenient().when(periodCalculator.toOffset(nullable(LocalDateTime.class)))
                .thenAnswer(invocation -> offset(invocation.getArgument(0)));
        lenient().when(homeGymLimits.snapshot()).thenReturn(LIMITS);
        lenient().when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.empty());
        lenient().when(homeGymRepository.findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                5L, HomeGymStatus.ACTIVE)).thenAnswer(invocation -> Optional.ofNullable(activeRow.get()));
        lenient().when(homeGymRepository.save(any(SubscriptionHomeGym.class))).thenAnswer(invocation -> {
            SubscriptionHomeGym row = invocation.getArgument(0);
            if (row.getStatus() == HomeGymStatus.ACTIVE) {
                activeRow.set(row);
            }
            return row;
        });
        lenient().when(gymVisitRepository.save(any(GymVisit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    private Subscription subscription() {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(5L);
        subscription.setUserId(9L);
        subscription.setPackageId(3L);
        subscription.setStatus("ACTIVE");
        subscription.setEntryMode(EntryMode.HOME_GYM_PENDING);
        subscription.setStartAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        subscription.setEndAt(LocalDateTime.of(2026, 12, 5, 9, 0));
        subscription.setTotalLimit(12);
        subscription.setRemainingLimit(10);
        return subscription;
    }

    private void givenSelectionState(Long soleGymId, long soleGymVisits) {
        lenient().when(selectionPolicy.evaluate(5L, PERIOD_START))
                .thenReturn(new HomeGymSelectionPolicy.SelectionState(
                        true, null, soleGymId, soleGymVisits, soleGymVisits,
                        soleGymId == null ? java.util.Map.of() : java.util.Map.of(soleGymId, soleGymVisits)));
    }

    private void givenGym(boolean found, boolean supportsPackage, boolean homeEligible) {
        lenient().when(catalogClient.getHomeGymInfo(100L, 3L)).thenReturn(
                new CatalogServiceGrpcClient.HomeGymInfo(found, "FitZone", supportsPackage, homeEligible, null));
    }

    // ------------------------------------------------------------------
    // Request validation and idempotency
    // ------------------------------------------------------------------

    @Test
    void missingSourceIsRejected() {
        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, null), "key-1", false));

        assertEquals("INVALID_SOURCE", error.getErrorCode());
        verifyNoInteractions(subscriptionRepository, homeGymRepository);
    }

    @Test
    void adminSourceRequiresAdminRights() {
        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.ADMIN), "key-1", false));

        assertEquals("INVALID_SOURCE", error.getErrorCode());
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    void customerSelectionRequiresIdempotencyKey() {
        var request = new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN);
        var error = assertThrows(az.fitnest.order.exception.BadRequestException.class,
                () -> service.selectHomeGym(9L, 5L, request, "  ", false));

        assertEquals("error.idempotency_key_required", error.getMessage());
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    void replayedIdempotencyKeyReturnsTheStoredResponse() throws Exception {
        HomeGymResponse stored = new HomeGymResponse(5L, "HOME_GYM", null, false, null, 0, 30, 30,
                false, null, false, new HomeGymResponse.StandardVisits(0, 12, 12),
                null, null, false, false, null, null, null);
        when(idempotencyRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(
                HomeGymIdempotency.builder()
                        .idempotencyKey("key-1")
                        .userId(9L)
                        .subscriptionId(5L)
                        .requestHash(hash("100|SUBSCRIPTION_SCREEN"))
                        .responseJson("stored")
                        .build()));
        when(objectMapper.readValue("stored", HomeGymResponse.class)).thenReturn(stored);

        HomeGymResponse response = service.selectHomeGym(9L, 5L,
                new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false);

        assertSame(stored, response);
        verifyNoInteractions(subscriptionRepository, homeGymRepository);
    }

    @Test
    void idempotencyKeyReusedForAnotherRequestIsAConflict() {
        when(idempotencyRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(
                HomeGymIdempotency.builder()
                        .idempotencyKey("key-1")
                        .userId(9L)
                        .subscriptionId(5L)
                        .requestHash(hash("200|SUBSCRIPTION_SCREEN"))
                        .responseJson("stored")
                        .build()));

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));

        assertEquals("IDEMPOTENCY_CONFLICT", error.getErrorCode());
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    void customerCannotSelectForAnotherUsersSubscription() {
        Subscription foreign = subscription();
        foreign.setUserId(8L);
        when(subscriptionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(foreign));

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));

        assertEquals("FORBIDDEN", error.getErrorCode());
    }

    // ------------------------------------------------------------------
    // Selectability
    // ------------------------------------------------------------------

    @Test
    void frozenSubscriptionCannotSelect() {
        Subscription frozen = subscription();
        frozen.setStatus("FROZEN");
        when(subscriptionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(frozen));

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));

        assertEquals("SUBSCRIPTION_FROZEN", error.getErrorCode());
    }

    @Test
    void expiredSubscriptionCannotSelect() {
        when(periodCalculator.isExpired(any(LocalDateTime.class))).thenReturn(true);

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));

        assertEquals("SUBSCRIPTION_INACTIVE", error.getErrorCode());
    }

    @Test
    void selectionIsClosedAfterVisitsAtTwoGyms() {
        when(selectionPolicy.evaluate(5L, PERIOD_START)).thenReturn(new HomeGymSelectionPolicy.SelectionState(
                false, HomeGymSelectionPolicy.MULTIPLE_GYMS_THIS_PERIOD, null, 0, 5,
                java.util.Map.of(100L, 3L, 200L, 2L)));

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));

        assertEquals("HOME_GYM_SELECTION_CLOSED", error.getErrorCode());
        verifyNoInteractions(catalogClient);
    }

    @Test
    void soleVisitedGymRestrictsSelectionToThatGym() {
        givenSelectionState(100L, 4L);

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(200L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));

        assertEquals("HOME_GYM_SELECTION_CLOSED", error.getErrorCode());
        verifyNoInteractions(catalogClient);
    }

    @Test
    void suggestedSourceRequiresASoleVisitedGym() {
        givenSelectionState(null, 0);

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUGGESTED), "key-1", false));

        assertEquals("INVALID_SOURCE", error.getErrorCode());
        verifyNoInteractions(catalogClient);
    }

    @Test
    void missingGymIsNotFound() {
        givenSelectionState(null, 0);
        givenGym(false, false, false);

        assertThrows(ResourceNotFoundException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));
    }

    @Test
    void gymOutsideThePackageIsRejected() {
        givenSelectionState(null, 0);
        givenGym(true, false, true);

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));

        assertEquals("GYM_NOT_IN_PACKAGE", error.getErrorCode());
        verify(homeGymRepository, never()).save(any(SubscriptionHomeGym.class));
    }

    @Test
    void gymWithHomeSwitchOffIsRejected() {
        givenSelectionState(null, 0);
        givenGym(true, true, false);

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));

        assertEquals("GYM_HOME_DISABLED", error.getErrorCode());
        verify(homeGymRepository, never()).save(any(SubscriptionHomeGym.class));
    }

    // ------------------------------------------------------------------
    // Selection and change
    // ------------------------------------------------------------------

    @Test
    void newSelectionPublishesTheEventAndStoresTheIdempotentResponse() {
        givenSelectionState(null, 0);
        givenGym(true, true, true);

        HomeGymResponse response = service.selectHomeGym(9L, 5L,
                new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false);

        assertEquals("HOME_GYM", response.entryMode());
        assertEquals(100L, response.homeGym().gymId());
        assertEquals("FitZone", response.homeGym().name());
        assertEquals("SUBSCRIPTION_SCREEN", response.homeGym().source());
        assertNotNull(activeRow.get());
        assertEquals(HomeGymStatus.ACTIVE, activeRow.get().getStatus());
        verify(subscriptionRepository).save(any(Subscription.class));
        verify(eventPublisher).publishSubscriptionEvent(eq(9L), eq("HOME_GYM_SELECTED"), eq(5L),
                any(SubscriptionEvent.class));
        verify(idempotencyRepository).save(any(HomeGymIdempotency.class));
    }

    @Test
    void selectingTheActiveGymReturnsTheCurrentStateWithoutANewEvent() {
        Subscription subscription = subscription();
        subscription.setEntryMode(EntryMode.HOME_GYM);
        when(subscriptionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(subscription));
        SubscriptionHomeGym active = activeHomeGym(100L);
        activeRow.set(active);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(active));
        givenGym(true, true, true);

        HomeGymResponse response = service.selectHomeGym(9L, 5L,
                new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false);

        assertEquals(100L, response.homeGym().gymId());
        assertEquals(HomeGymStatus.ACTIVE, active.getStatus());
        verify(homeGymRepository, never()).save(any(SubscriptionHomeGym.class));
        verifyNoInteractions(eventPublisher);
        verify(idempotencyRepository, never()).save(any(HomeGymIdempotency.class));
    }

    @Test
    void changingHomeGymIsBlockedAfterAVisit() {
        Subscription subscription = subscription();
        subscription.setEntryMode(EntryMode.HOME_GYM);
        when(subscriptionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(subscription));
        SubscriptionHomeGym active = activeHomeGym(100L);
        activeRow.set(active);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(active));
        when(catalogClient.getHomeGymInfo(200L, 3L)).thenReturn(
                new CatalogServiceGrpcClient.HomeGymInfo(true, "NeoGym", true, true, null));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                5L, PERIOD_START, VisitEntryType.HOME_UNLIMITED, 100L)).thenReturn(1L);

        HomeGymException error = assertThrows(HomeGymException.class, () -> service.selectHomeGym(
                9L, 5L, new HomeGymSelectRequest(200L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false));

        assertEquals("HOME_GYM_CHANGE_LOCKED", error.getErrorCode());
        assertEquals(HomeGymStatus.ACTIVE, active.getStatus());
        verify(homeGymRepository, never()).save(any(SubscriptionHomeGym.class));
    }

    @Test
    void changingHomeGymClosesThePreviousRowAndPublishesTheChange() {
        Subscription subscription = subscription();
        subscription.setEntryMode(EntryMode.HOME_GYM);
        when(subscriptionRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(subscription));
        SubscriptionHomeGym previous = activeHomeGym(100L);
        activeRow.set(previous);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(previous));
        givenGym(true, true, true);

        HomeGymResponse response = service.selectHomeGym(9L, 5L,
                new HomeGymSelectRequest(100L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-1", false);

        // Selecting the same gym is a no-op...
        assertEquals(100L, response.homeGym().gymId());
        verifyNoInteractions(eventPublisher);

        // ...while a real change closes the old row first.
        activeRow.set(previous);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(previous));
        when(catalogClient.getHomeGymInfo(200L, 3L)).thenReturn(
                new CatalogServiceGrpcClient.HomeGymInfo(true, "NeoGym", true, true, null));

        HomeGymResponse changed = service.selectHomeGym(9L, 5L,
                new HomeGymSelectRequest(200L, HomeGymSource.SUBSCRIPTION_SCREEN), "key-2", false);

        assertEquals(200L, changed.homeGym().gymId());
        assertEquals(HomeGymStatus.CHANGED, previous.getStatus());
        assertEquals(HomeGymEndReason.CHANGE, previous.getEndReason());
        assertEquals(NOW, previous.getEndedAt());
        assertEquals(HomeGymStatus.ACTIVE, activeRow.get().getStatus());
        assertEquals(200L, activeRow.get().getGymId());
        verify(eventPublisher).publishSubscriptionEvent(eq(9L), eq("HOME_GYM_CHANGED"), eq(5L),
                any(SubscriptionEvent.class));
    }

    @Test
    void adminSelectionSkipsTheIdempotencyRequirement() {
        givenSelectionState(null, 0);
        givenGym(true, true, true);

        HomeGymResponse response = service.selectHomeGym(null, 5L,
                new HomeGymSelectRequest(100L, HomeGymSource.ADMIN), null, true);

        assertEquals(100L, response.homeGym().gymId());
        verifyNoInteractions(idempotencyRepository);
    }

    // ------------------------------------------------------------------
    // R8/FIRST_QR and R19 conversion
    // ------------------------------------------------------------------

    @Test
    void firstQrSelectionPromotesTodaysStandardVisit() {
        givenSelectionState(null, 0);
        givenGym(true, true, true);
        GymVisit today = visit(31L, VisitEntryType.STANDARD, NOW.minusHours(1));
        when(gymVisitRepository.findFirstBySubscriptionIdAndGymIdAndEntryTypeAndPeriodStartOrderByCheckedInAtDesc(
                5L, 100L, VisitEntryType.STANDARD, PERIOD_START)).thenReturn(Optional.of(today));

        service.selectHomeGym(9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.FIRST_QR), "key-1", false);

        assertEquals(VisitEntryType.HOME_UNLIMITED, today.getEntryType());
        assertEquals(100L, today.getHomeGymId());
        // The unit consumed by the standard entry is given back (10 -> 11).
        verify(subscriptionRepository).save(any(Subscription.class));
        assertEquals(11, subscriptionRepository.findByIdForUpdate(5L).orElseThrow().getRemainingLimit());
    }

    @Test
    void firstQrSelectionLeavesYesterdaysVisitStandard() {
        givenSelectionState(null, 0);
        givenGym(true, true, true);
        GymVisit yesterday = visit(31L, VisitEntryType.STANDARD, NOW.minusDays(1));
        when(gymVisitRepository.findFirstBySubscriptionIdAndGymIdAndEntryTypeAndPeriodStartOrderByCheckedInAtDesc(
                5L, 100L, VisitEntryType.STANDARD, PERIOD_START)).thenReturn(Optional.of(yesterday));

        service.selectHomeGym(9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.FIRST_QR), "key-1", false);

        assertEquals(VisitEntryType.STANDARD, yesterday.getEntryType());
        assertNull(yesterday.getHomeGymId());
        verify(gymVisitRepository, never()).save(any(GymVisit.class));
        assertEquals(10, subscriptionRepository.findByIdForUpdate(5L).orElseThrow().getRemainingLimit());
    }

    @Test
    void r19SelectionConvertsTheMonthsVisitsAtTheSoleGym() {
        givenSelectionState(100L, 2L);
        givenGym(true, true, true);
        GymVisit first = visit(31L, VisitEntryType.STANDARD, NOW.minusDays(5));
        GymVisit second = visit(32L, VisitEntryType.STANDARD, NOW.minusDays(3));
        GymVisit alreadyFree = visit(33L, VisitEntryType.STANDARD, NOW.minusDays(2));
        alreadyFree.setCountsTowardsLimit(false);
        when(gymVisitRepository.findBySubscriptionIdAndGymIdAndPeriodStartOrderByCheckedInAtAsc(
                5L, 100L, PERIOD_START)).thenReturn(List.of(first, second, alreadyFree));

        service.selectHomeGym(9L, 5L, new HomeGymSelectRequest(100L, HomeGymSource.SUGGESTED), "key-1", false);

        assertEquals(VisitEntryType.HOME_UNLIMITED, first.getEntryType());
        assertEquals(VisitEntryType.HOME_UNLIMITED, second.getEntryType());
        assertEquals(VisitEntryType.STANDARD, alreadyFree.getEntryType());
        assertEquals(100L, first.getHomeGymId());
        // Two units are given back and capped by the subscription total limit (12).
        assertEquals(12, subscriptionRepository.findByIdForUpdate(5L).orElseThrow().getRemainingLimit());
        verify(eventPublisher).publishSubscriptionEvent(eq(9L), eq("HOME_GYM_SELECTED"), eq(5L),
                any(SubscriptionEvent.class));
    }

    // ------------------------------------------------------------------
    // R13 upgrade hook
    // ------------------------------------------------------------------

    @Test
    void upgradeEndsTheSelectionWhenTheNewPackageLacksTheGym() {
        Subscription subscription = subscription();
        subscription.setEntryMode(EntryMode.HOME_GYM);
        SubscriptionHomeGym active = activeHomeGym(100L);
        activeRow.set(active);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(active));
        when(catalogClient.getHomeGymInfo(100L, 9L))
                .thenReturn(new CatalogServiceGrpcClient.HomeGymInfo(true, "FitZone", false, true, null));

        service.onUpgrade(subscription, 9L, "Bronze");

        assertEquals(HomeGymStatus.ENDED, active.getStatus());
        assertEquals(HomeGymEndReason.UPGRADE_NOT_IN_PACKAGE, active.getEndReason());
        assertEquals(EntryMode.STANDARD, subscription.getEntryMode());
        assertEquals("FitZone", captureEvent().getGymName());
        assertEquals("UPGRADE_NOT_IN_PACKAGE", captureEvent().getEndReason());
    }

    @Test
    void upgradeKeepsTheSelectionWhenTheNewPackageSupportsTheGym() {
        Subscription subscription = subscription();
        subscription.setEntryMode(EntryMode.HOME_GYM);
        SubscriptionHomeGym active = activeHomeGym(100L);
        activeRow.set(active);
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(active));
        when(catalogClient.getHomeGymInfo(100L, 9L))
                .thenReturn(new CatalogServiceGrpcClient.HomeGymInfo(true, "FitZone", true, true, null));

        service.onUpgrade(subscription, 9L, "Platinum");

        assertEquals(HomeGymStatus.ACTIVE, active.getStatus());
        assertEquals(EntryMode.HOME_GYM, subscription.getEntryMode());
        verifyNoInteractions(eventPublisher);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private SubscriptionEvent captureEvent() {
        var captor = org.mockito.ArgumentCaptor.forClass(SubscriptionEvent.class);
        verify(eventPublisher).publishSubscriptionEvent(eq(9L), any(), eq(5L), captor.capture());
        return captor.getValue();
    }

    private static SubscriptionHomeGym activeHomeGym(Long gymId) {
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

    private static GymVisit visit(Long id, VisitEntryType type, LocalDateTime checkedInAt) {
        return GymVisit.builder()
                .id(id)
                .gymId(100L)
                .subscriptionId(5L)
                .entryType(type)
                .checkedInAt(checkedInAt)
                .periodStart(PERIOD_START)
                .countsTowardsLimit(true)
                .build();
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(HomeGymPeriodCalculator.BAKU).toOffsetDateTime();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
