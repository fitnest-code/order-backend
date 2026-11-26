package az.fitnest.order.service.freeze;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.dto.freeze.FreezeCommitRequest;
import az.fitnest.order.dto.freeze.FreezeCommitResponse;
import az.fitnest.order.dto.freeze.FreezeEligibilityResponse;
import az.fitnest.order.dto.freeze.FreezePreviewResponse;
import az.fitnest.order.dto.freeze.ResumeCommitRequest;
import az.fitnest.order.dto.freeze.ResumeCommitResponse;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ConflictException;
import az.fitnest.order.exception.ForbiddenException;
import az.fitnest.order.model.entity.FreezeIdempotencyRecord;
import az.fitnest.order.model.entity.FreezePreview;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionFreeze;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.model.entity.SubscriptionFreezeEntitlement;
import az.fitnest.order.model.enums.FreezeStatus;
import az.fitnest.order.repository.FreezeAuditEventRepository;
import az.fitnest.order.repository.FreezeOutboxEventRepository;
import az.fitnest.order.repository.SubscriptionFreezeEntitlementRepository;
import az.fitnest.order.repository.SubscriptionFreezeRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import az.fitnest.order.service.TranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionFreezeLimitsTest {

    private static final Long USER_ID = 33L;
    private static final Long SUBSCRIPTION_ID = 44L;
    private static final Long FREEZE_ID = 55L;

    @Mock private SubscriptionFreezeRepository freezeRepository;
    @Mock private SubscriptionFreezeEntitlementRepository entitlementRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionPackageRepository subscriptionPackageRepository;
    @Mock private FreezeEntitlementService entitlementService;
    @Mock private FreezePreviewService previewService;
    @Mock private FreezeIdempotencyService idempotencyService;
    @Mock private FreezeAuditEventRepository auditEventRepository;
    @Mock private FreezeOutboxEventRepository outboxEventRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private CatalogServiceGrpcClient catalogServiceGrpcClient;
    @Mock private FreezeFinalizeService freezeFinalizeService;
    @Mock private TranslationService translationService;
    @Spy private FreezeTierPolicyProvider freezeTierPolicyProvider = new FreezeTierPolicyProvider();

    @InjectMocks private SubscriptionFreezeService service;

    @ParameterizedTest
    @CsvSource({
            "Bronze, 5",
            "Silver, 7",
            "Gold, 10",
            "Platinum, 12"
    })
    void eligibilityExposesNewTierLimitToMobile(String packageName, int limit) {
        stubEligibility(packageName, limit, 0, 0);

        FreezeEligibilityResponse response = service.getEligibility(USER_ID, SUBSCRIPTION_ID);

        assertTrue(response.eligible());
        assertEquals(limit, response.totalDays());
        assertEquals(limit, response.availableDays());
    }

    @ParameterizedTest
    @CsvSource({
            "Bronze, 5",
            "Silver, 7",
            "Gold, 10",
            "Platinum, 12"
    })
    void previewRejectsDaysAboveTierAsInvalidDays(String packageName, int limit) {
        stubEligibility(packageName, limit, 0, 0);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> service.previewFreeze(USER_ID, SUBSCRIPTION_ID, limit + 1));

        assertEquals("error.freeze.invalid_days", exception.getMessage());
        assertEquals(400, exception.getHttpStatus().value());
        verifyNoInteractions(catalogServiceGrpcClient, previewService);
    }

    @Test
    void previewRejectsValidTierDaysAboveRemainingBalanceAsLimitExceeded() {
        // Bronze limit is 5, but only 1 day is left this month.
        stubEligibility("Bronze", 5, 3, 1);

        ConflictException exception = assertThrows(ConflictException.class,
                () -> service.previewFreeze(USER_ID, SUBSCRIPTION_ID, 2));

        assertEquals("error.freeze.limit_exceeded", exception.getMessage());
        assertEquals(409, exception.getHttpStatus().value());
        verifyNoInteractions(catalogServiceGrpcClient, previewService);
    }

    @Test
    void earlyActivationUsesNewPlatinumLimitForUsedAndReturnedDays() {
        LocalDateTime startAt = LocalDateTime.now().minusDays(2).plusMinutes(1);
        SubscriptionFreeze freeze = SubscriptionFreeze.builder()
                .id(FREEZE_ID)
                .subscriptionId(SUBSCRIPTION_ID)
                .userId(USER_ID)
                .requestedDays(12)
                .startAt(startAt)
                .planEndAt(startAt.plusDays(12))
                .expiryBefore(startAt.plusDays(12))
                .status(FreezeStatus.ACTIVE)
                .version(0L)
                .build();
        when(idempotencyService.findRecord(eq(USER_ID), any(), any())).thenReturn(Optional.empty());
        when(freezeRepository.findByIdForUpdate(FREEZE_ID)).thenReturn(Optional.of(freeze));
        when(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(subscription()));

        ResumeCommitResponse response = service.resumeCommit(
                USER_ID, FREEZE_ID, new ResumeCommitRequest(2), "early-activation-key");

        assertEquals(12, response.requestedDays());
        assertEquals(2, response.usedDays());
        assertEquals(10, response.returnedDays());
        verify(entitlementService).finaliseConsumed(SUBSCRIPTION_ID, 2, 10, startAt);
    }

    @ParameterizedTest
    @CsvSource({
            "Bronze, 5",
            "Silver, 7",
            "Gold, 10",
            "Platinum, 12"
    })
    void previewAcceptsBoundaryDaysForEachTier(String packageName, int limit) {
        stubEligibility(packageName, limit, 0, 0);
        when(previewService.createPreview(eq(USER_ID), any(Subscription.class), eq(limit)))
                .thenReturn(previewResponse(limit));

        FreezePreviewResponse response = service.previewFreeze(USER_ID, SUBSCRIPTION_ID, limit);

        assertEquals(limit, response.requestedDays());
        verify(previewService).createPreview(eq(USER_ID), any(Subscription.class), eq(limit));
    }

    @Test
    void previewRejectsZeroAndNegativeDays() {
        stubEligibility("Bronze", 5, 0, 0);

        assertThrows(BadRequestException.class, () -> service.previewFreeze(USER_ID, SUBSCRIPTION_ID, 0));
        assertThrows(BadRequestException.class, () -> service.previewFreeze(USER_ID, SUBSCRIPTION_ID, -1));
        verifyNoInteractions(previewService, catalogServiceGrpcClient);
    }

    @Test
    void previewRejectsBookingConflict() {
        stubEligibility("Bronze", 5, 0, 0);
        when(catalogServiceGrpcClient.hasActiveReservations(any(), any(), any())).thenReturn(true);

        ConflictException exception = assertThrows(ConflictException.class,
                () -> service.previewFreeze(USER_ID, SUBSCRIPTION_ID, 5));

        assertEquals("error.freeze.booking_conflict", exception.getMessage());
        verifyNoInteractions(previewService);
    }

    @Test
    void eligibilityCapsLegacyCumulativeBudgetAtTierLimit() {
        stubEligibility("Gold", 30, 5, 0);

        FreezeEligibilityResponse response = service.getEligibility(USER_ID, SUBSCRIPTION_ID);

        assertEquals(10, response.totalDays());
        assertEquals(10, response.availableDays());
    }

    @Test
    void previewRejectsAboveTierEvenWhenLegacyBudgetIsHigher() {
        stubEligibility("Gold", 30, 0, 0);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> service.previewFreeze(USER_ID, SUBSCRIPTION_ID, 11));

        assertEquals("error.freeze.invalid_days", exception.getMessage());
        verifyNoInteractions(previewService, catalogServiceGrpcClient);
    }

    @Test
    void eligibilityRejectsDifferentUser() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));

        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> service.getEligibility(999L, SUBSCRIPTION_ID));

        assertEquals("error.subscription_ownership_mismatch", exception.getMessage());
        verifyNoInteractions(entitlementService, freezeRepository);
    }

    @Test
    void resumeCommitRejectsDifferentUser() {
        when(idempotencyService.findRecord(eq(999L), anyString(), eq("ownership-key")))
                .thenReturn(Optional.empty());
        when(freezeRepository.findByIdForUpdate(FREEZE_ID))
                .thenReturn(Optional.of(freeze(FreezeStatus.ACTIVE, 5, LocalDateTime.now().minusDays(1))));

        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> service.resumeCommit(999L, FREEZE_ID, null, "ownership-key"));

        assertEquals("error.freeze_ownership_mismatch", exception.getMessage());
        verifyNoInteractions(entitlementService, auditEventRepository, outboxEventRepository);
    }

    @Test
    void eligibilityDeniedWhenAlreadyFrozen() {
        stubEligibility("Gold", 10, 2, 0);
        SubscriptionFreeze active = freeze(FreezeStatus.ACTIVE, 4, LocalDateTime.now().minusHours(1));
        when(freezeRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, FreezeStatus.ACTIVE))
                .thenReturn(Optional.of(active));

        FreezeEligibilityResponse response = service.getEligibility(USER_ID, SUBSCRIPTION_ID);

        assertFalse(response.eligible());
        assertEquals("error.freeze.already_frozen", response.reason());
        assertTrue(response.currentlyFrozen());
        assertEquals(FREEZE_ID, response.activeFreezeId());
    }

    @Test
    void eligibilityDeniedForInactiveAndExpiredSubscriptions() {
        Subscription sub = stubEligibility("Bronze", 5, 0, 0);
        sub.setStatus("CANCELLED");
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));

        FreezeEligibilityResponse inactive = service.getEligibility(USER_ID, SUBSCRIPTION_ID);
        assertFalse(inactive.eligible());
        assertEquals("error.subscription_not_active", inactive.reason());

        sub.setStatus("ACTIVE");
        sub.setEndAt(LocalDateTime.now().minusMinutes(1));
        FreezeEligibilityResponse expired = service.getEligibility(USER_ID, SUBSCRIPTION_ID);
        assertFalse(expired.eligible());
        assertEquals("error.subscription_expired", expired.reason());
    }

    @Test
    void eligibilityDeniedWhenBudgetExhausted() {
        stubEligibility("Platinum", 12, 12, 0);

        FreezeEligibilityResponse response = service.getEligibility(USER_ID, SUBSCRIPTION_ID);

        assertFalse(response.eligible());
        assertEquals("error.freeze_days_exhausted", response.reason());
        assertEquals(0, response.availableDays());
    }

    @Test
    void commitFreezeReservesDaysAndPublishesEvents() {
        LocalDateTime now = LocalDateTime.now();
        FreezePreview preview = FreezePreview.builder()
                .subscriptionId(SUBSCRIPTION_ID)
                .userId(USER_ID)
                .requestedDays(5)
                .planEndAt(now.plusDays(5))
                .expiryAfter(now.plusDays(35))
                .expectedVersion(0L)
                .expiresAt(now.plusMinutes(15))
                .build();
        Subscription sub = subscription();
        SubscriptionFreezeEntitlement entitlement = entitlement(5, 0, 0);
        when(idempotencyService.findRecord(eq(USER_ID), anyString(), eq("commit-key")))
                .thenReturn(Optional.empty());
        when(previewService.getValidPreview(1L, USER_ID)).thenReturn(preview);
        when(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        when(freezeRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, FreezeStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(catalogServiceGrpcClient.hasActiveReservations(any(), any(), any())).thenReturn(false);
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));
        when(freezeRepository.save(any())).thenAnswer(invocation -> {
            SubscriptionFreeze saved = invocation.getArgument(0);
            saved.setId(FREEZE_ID);
            return saved;
        });

        FreezeCommitResponse response = service.commitFreeze(
                USER_ID, SUBSCRIPTION_ID, new FreezeCommitRequest("1"), "commit-key");

        assertEquals(FREEZE_ID, response.freezeId());
        assertEquals(5, response.requestedDays());
        assertEquals(FreezeStatus.ACTIVE, response.status());
        assertEquals(5, entitlement.getReservedDays());
        assertEquals(0, entitlement.getConsumedDays());
        assertEquals("FROZEN", sub.getStatus());
        verify(auditEventRepository).save(any());
        verify(outboxEventRepository).save(any());
        verify(idempotencyService).saveRecord(eq(USER_ID), anyString(), eq("commit-key"), any(), any());
    }

    @Test
    void commitFreezeReplaysCachedResponseWithoutSecondReservation() throws Exception {
        FreezeCommitResponse cached = new FreezeCommitResponse(
                FREEZE_ID, SUBSCRIPTION_ID, 5, LocalDateTime.now(), LocalDateTime.now().plusDays(5),
                LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(35), FreezeStatus.ACTIVE);
        when(idempotencyService.findRecord(eq(USER_ID), anyString(), eq("replay-key"))).thenReturn(Optional.of(
                FreezeIdempotencyRecord.builder().responseBody("cached-json").build()));
        when(objectMapper.readValue("cached-json", FreezeCommitResponse.class)).thenReturn(cached);

        FreezeCommitResponse response = service.commitFreeze(
                USER_ID, SUBSCRIPTION_ID, new FreezeCommitRequest("1"), "replay-key");

        assertSame(cached, response);
        verifyNoInteractions(previewService, entitlementRepository, freezeRepository,
                auditEventRepository, outboxEventRepository);
    }

    @Test
    void commitFreezeRejectsBlankIdempotencyKey() {
        assertThrows(BadRequestException.class,
                () -> service.commitFreeze(USER_ID, SUBSCRIPTION_ID, new FreezeCommitRequest("1"), " "));
        verifyNoInteractions(previewService);
    }

    @Test
    void commitFreezeRejectsStalePreviewVersion() {
        FreezePreview preview = FreezePreview.builder()
                .subscriptionId(SUBSCRIPTION_ID)
                .userId(USER_ID)
                .requestedDays(5)
                .planEndAt(LocalDateTime.now().plusDays(5))
                .expiryAfter(LocalDateTime.now().plusDays(35))
                .expectedVersion(1L)
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .build();
        when(idempotencyService.findRecord(eq(USER_ID), anyString(), eq("commit-key")))
                .thenReturn(Optional.empty());
        when(previewService.getValidPreview(1L, USER_ID)).thenReturn(preview);
        when(subscriptionRepository.findByIdForUpdate(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));

        ConflictException exception = assertThrows(ConflictException.class,
                () -> service.commitFreeze(USER_ID, SUBSCRIPTION_ID, new FreezeCommitRequest("1"), "commit-key"));

        assertEquals("error.freeze.preview_changed", exception.getMessage());
        verifyNoInteractions(entitlementRepository, auditEventRepository, outboxEventRepository);
    }

    @Test
    void resumeCommitRejectsExpectedUsedDaysMismatch() {
        LocalDateTime startAt = LocalDateTime.now().minusDays(2).plusMinutes(1);
        SubscriptionFreeze freeze = freeze(FreezeStatus.ACTIVE, 12, startAt);
        when(idempotencyService.findRecord(eq(USER_ID), anyString(), eq("resume-key")))
                .thenReturn(Optional.empty());
        when(freezeRepository.findByIdForUpdate(FREEZE_ID)).thenReturn(Optional.of(freeze));

        ConflictException exception = assertThrows(ConflictException.class,
                () -> service.resumeCommit(USER_ID, FREEZE_ID, new ResumeCommitRequest(3), "resume-key"));

        assertEquals("error.freeze.preview_changed", exception.getMessage());
        verifyNoInteractions(entitlementService);
    }

    @Test
    void resumeCommitAtPlanEndDelegatesToFinalize() {
        LocalDateTime startAt = LocalDateTime.now().minusDays(12);
        SubscriptionFreeze freeze = freeze(FreezeStatus.ACTIVE, 12, startAt);
        freeze.setPlanEndAt(LocalDateTime.now().minusDays(1));
        when(idempotencyService.findRecord(eq(USER_ID), anyString(), eq("resume-key")))
                .thenReturn(Optional.empty());
        when(freezeRepository.findByIdForUpdate(FREEZE_ID)).thenReturn(Optional.of(freeze));
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));

        ResumeCommitResponse response = service.resumeCommit(
                USER_ID, FREEZE_ID, new ResumeCommitRequest(null), "resume-key");

        assertEquals(12, response.usedDays());
        assertEquals(0, response.returnedDays());
        assertTrue(response.planEndReached());
        verify(freezeFinalizeService).complete(freeze);
        verify(entitlementService, never()).finaliseConsumed(anyLong(), anyInt(), anyInt(), any());
    }

    private FreezePreviewResponse previewResponse(int days) {
        LocalDateTime now = LocalDateTime.now();
        return new FreezePreviewResponse("p1", SUBSCRIPTION_ID, days, now.plusDays(30),
                now.plusDays(30 + days), now, now.plusDays(days), now.plusMinutes(15));
    }

    private SubscriptionFreeze freeze(FreezeStatus status, int requestedDays, LocalDateTime startAt) {
        return SubscriptionFreeze.builder()
                .id(FREEZE_ID)
                .subscriptionId(SUBSCRIPTION_ID)
                .userId(USER_ID)
                .requestedDays(requestedDays)
                .startAt(startAt)
                .planEndAt(startAt.plusDays(requestedDays))
                .expiryBefore(startAt.plusDays(requestedDays))
                .status(status)
                .version(0L)
                .build();
    }

    private Subscription stubEligibility(String packageName, int totalDays, int consumedDays, int reservedDays) {
        SubscriptionPackage pkg = new SubscriptionPackage();
        pkg.setId(1L);
        pkg.setName(packageName);
        Subscription sub = subscription();
        when(subscriptionPackageRepository.findById(1L)).thenReturn(Optional.of(pkg));
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        when(entitlementService.getBySubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(
                SubscriptionFreezeEntitlement.builder()
                        .subscriptionId(SUBSCRIPTION_ID)
                        .userId(USER_ID)
                        .totalDays(totalDays)
                        .consumedDays(consumedDays)
                        .reservedDays(reservedDays)
                        .policyVersion("v1.2")
                        .allowanceMonth("2026-11")
                        .build()));
        when(freezeRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, FreezeStatus.ACTIVE))
                .thenReturn(Optional.empty());
        return sub;
    }

    private SubscriptionFreezeEntitlement entitlement(int totalDays, int consumedDays, int reservedDays) {
        return SubscriptionFreezeEntitlement.builder()
                .subscriptionId(SUBSCRIPTION_ID)
                .userId(USER_ID)
                .totalDays(totalDays)
                .consumedDays(consumedDays)
                .reservedDays(reservedDays)
                .policyVersion("v1.2")
                .allowanceMonth("2026-11")
                .build();
    }

    private Subscription subscription() {
        Subscription sub = new Subscription();
        sub.setSubscriptionId(SUBSCRIPTION_ID);
        sub.setUserId(USER_ID);
        sub.setPackageId(1L);
        sub.setStatus("ACTIVE");
        sub.setStartAt(LocalDateTime.now().minusDays(1));
        sub.setEndAt(LocalDateTime.now().plusDays(30));
        sub.setVersion(0L);
        return sub;
    }
}
