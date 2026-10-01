package az.fitnest.order.service.impl;

import az.fitnest.order.dto.AdminAssignSubscriptionRequest;
import az.fitnest.order.dto.AdminAssignSubscriptionResponse;
import az.fitnest.order.dto.BonusDecision;
import az.fitnest.order.dto.PurchaseKind;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.grpc.NotificationGrpcClient;
import az.fitnest.order.grpc.PaymentGrpcClient;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.repository.*;
import az.fitnest.order.service.CampaignEligibilityService;
import az.fitnest.order.service.TranslationService;
import az.fitnest.order.service.freeze.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Campaign-related unit tests for {@link UserSubscriptionService}:
 * {@code assignSubscriptionToUser} (bonus application + redemption race fallback)
 * and {@code revokeCampaignBonusForRefund} (both branches).
 *
 * <p>The clock inside the service is {@code LocalDateTime.now(BAKU)} and cannot be injected,
 * so time assertions are relationship-based (e.g. {@code endAt == startAt + paid + bonus}
 * months, with {@code startAt} pinned between two wall-clock readings taken around the call).
 */
@ExtendWith(MockitoExtension.class)
class UserSubscriptionServiceCampaignTest {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionPackageRepository packageRepository;
    @Mock private GymVisitRepository gymVisitRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private SubscriptionEventPublisher subscriptionEventPublisher;
    @Mock private TranslationService translationService;
    @Mock private PaymentGrpcClient paymentGrpcClient;
    @Mock private NotificationGrpcClient notificationGrpcClient;
    @Mock private jakarta.persistence.EntityManager entityManager;
    @Mock private FreezeEntitlementService freezeEntitlementService;
    @Mock private FreezeTierPolicyProvider freezeTierPolicyProvider;
    @Mock private SubscriptionFreezeService subscriptionFreezeService;
    @Mock private SubscriptionFreezeRepository subscriptionFreezeRepository;
    @Mock private FreezeFinalizeService freezeFinalizeService;
    @Mock private CampaignEligibilityService campaignEligibilityService;
    @Mock private UserCampaignRedemptionRepository userCampaignRedemptionRepository;
    // Extra repositories needed by the REAL CampaignEligibilityService used in one scenario.
    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignOfferRepository campaignOfferRepository;
    @Mock private CampaignTermRepository campaignTermRepository;
    @Mock private CampaignImpressionRepository campaignImpressionRepository;

    @InjectMocks
    private UserSubscriptionService service;

    private SubscriptionPackage pkg;

    @BeforeEach
    void setUp() {
        pkg = pkgWithOption(7L, 3);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private SubscriptionPackage pkgWithOption(long optionId, int durationMonths) {
        SubscriptionPackage p = new SubscriptionPackage();
        p.setName("Premium");
        p.setIsActive(true);
        p.setEntryLimit(20);
        PackageOption option = new PackageOption();
        option.setId(optionId);
        option.setDurationMonths(durationMonths);
        option.setEntryLimit(null); // exercises the pkg.getEntryLimit() fallback
        p.setOptions(new java.util.HashSet<>(Set.of(option)));
        return p;
    }

    private AdminAssignSubscriptionRequest request(Boolean applyCampaign, Boolean autoPay) {
        return AdminAssignSubscriptionRequest.builder()
                .userId(100L)
                .planId(5L)
                .optionId(7L)
                .applyCampaign(applyCampaign)
                .autoPaymentEnabled(autoPay)
                .build();
    }

    private void stubSaveAssignsId(long id) {
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            if (s.getSubscriptionId() == null) {
                s.setSubscriptionId(id);
            }
            return s;
        });
    }

    private BonusDecision eligibleDecision(int baseMonths, int bonusMonths) {
        return BonusDecision.builder()
                .applied(true)
                .campaignId(1L)
                .bonusMonths(bonusMonths)
                .paidMonths(baseMonths)
                .totalMonths(baseMonths + bonusMonths)
                .campaignLabel("label")
                .build();
    }

    /**
     * Builds a service instance whose campaign collaborator is the REAL
     * {@link CampaignEligibilityService} (sharing the same repository mocks), so that the
     * existing-subscription eligibility check is exercised end-to-end.
     */
    private UserSubscriptionService serviceWithRealCampaignEligibility() {
        CampaignEligibilityService realCampaignService = new CampaignEligibilityService(
                campaignRepository, campaignOfferRepository, campaignTermRepository,
                userCampaignRedemptionRepository, campaignImpressionRepository,
                subscriptionRepository, translationService);
        return new UserSubscriptionService(
                subscriptionRepository, packageRepository, gymVisitRepository, orderRepository,
                subscriptionEventPublisher, translationService, paymentGrpcClient, notificationGrpcClient,
                entityManager, freezeEntitlementService, freezeTierPolicyProvider, subscriptionFreezeService,
                subscriptionFreezeRepository, freezeFinalizeService,
                realCampaignService, userCampaignRedemptionRepository, campaignOfferRepository);
    }

    private Subscription campaignSubscription(long id, Long campaignId, LocalDateTime paidUntil,
                                              LocalDateTime endAt, int bonusMonths) {
        Subscription s = new Subscription();
        s.setSubscriptionId(id);
        s.setUserId(100L);
        s.setPackageId(5L);
        s.setOptionId(7L);
        s.setStatus("ACTIVE");
        s.setStartAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        s.setEndAt(endAt);
        s.setPaidUntil(paidUntil);
        s.setPaidDurationMonths(3);
        s.setBonusMonths(bonusMonths);
        s.setCampaignId(campaignId);
        return s;
    }

    // ------------------------------------------------------------------
    // assignSubscriptionToUser
    // ------------------------------------------------------------------

    @Test
    void assign_applyCampaignEligible_bonusAppliedAndRedemptionPersisted() {
        when(campaignEligibilityService.getBakuZone()).thenReturn(BAKU);
        when(packageRepository.findById(5L)).thenReturn(Optional.of(pkg));
        when(campaignEligibilityService.resolve(eq(100L), eq(5L), eq(3),
                eq(PurchaseKind.NEW_PURCHASE), any(LocalDateTime.class)))
                .thenReturn(eligibleDecision(3, 1));
        stubSaveAssignsId(555L);

        LocalDateTime before = LocalDateTime.now(BAKU);
        AdminAssignSubscriptionResponse resp = service.assignSubscriptionToUser(request(true, null));
        LocalDateTime after = LocalDateTime.now(BAKU);

        // Response contract
        assertTrue(resp.campaignApplied());
        assertEquals(1, resp.bonusMonths());
        assertEquals(1L, resp.campaignId());
        assertEquals(3, resp.durationMonths());
        assertEquals("ACTIVE", resp.status());
        assertEquals(555L, resp.subscriptionId());
        assertEquals(100L, resp.userId());
        assertEquals("Premium", resp.planName());
        assertEquals(7L, resp.optionId());

        // Saved subscription: relationships, not absolutes (clock is internal)
        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository, times(1)).save(subCaptor.capture());
        Subscription saved = subCaptor.getValue();
        assertEquals(3, saved.getPaidDurationMonths());
        assertEquals(1, saved.getBonusMonths());
        assertEquals(1L, saved.getCampaignId());
        assertEquals(saved.getStartAt().plusMonths(3), saved.getPaidUntil(),
                "paidUntil = now + paid months");
        assertEquals(saved.getStartAt().plusMonths(4), saved.getEndAt(),
                "endAt = now + paid + bonus months");
        assertTrue(saved.getEndAt().isAfter(saved.getPaidUntil()));
        assertFalse(saved.getStartAt().isBefore(before));
        assertFalse(saved.getStartAt().isAfter(after));

        // Redemption row
        ArgumentCaptor<UserCampaignRedemption> redCaptor = ArgumentCaptor.forClass(UserCampaignRedemption.class);
        verify(userCampaignRedemptionRepository).save(redCaptor.capture());
        UserCampaignRedemption redemption = redCaptor.getValue();
        assertEquals(100L, redemption.getUserId());
        assertEquals(1L, redemption.getCampaignId());
        assertEquals(555L, redemption.getSubscriptionId());
        assertEquals(saved.getStartAt(), redemption.getRedeemedAt());

        verify(subscriptionEventPublisher).publishSubscriptionEvent(100L, "ASSIGNED", 555L);
        verify(freezeEntitlementService).createForSubscription(same(saved), eq("Premium"), any());
    }

    @Test
    void assign_applyCampaignDisabled_noBonusNoRedemptionCampaignIdNull() {
        when(campaignEligibilityService.getBakuZone()).thenReturn(BAKU);
        when(packageRepository.findById(5L)).thenReturn(Optional.of(pkg));
        stubSaveAssignsId(555L);

        // applyCampaign = null (admin UI default) -> campaign path not even consulted
        AdminAssignSubscriptionResponse resp = service.assignSubscriptionToUser(request(null, null));

        assertFalse(resp.campaignApplied());
        assertEquals(0, resp.bonusMonths());
        assertNull(resp.campaignId());
        assertEquals(3, resp.durationMonths());

        verify(campaignEligibilityService, never())
                .resolve(any(), any(), any(), any(), any());
        verifyNoInteractions(userCampaignRedemptionRepository);

        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository, times(1)).save(subCaptor.capture());
        Subscription saved = subCaptor.getValue();
        assertEquals(0, saved.getBonusMonths());
        assertNull(saved.getCampaignId());
        assertEquals(3, saved.getPaidDurationMonths());
        assertEquals(saved.getPaidUntil(), saved.getEndAt(), "without bonus endAt == paidUntil");
        assertEquals(saved.getStartAt().plusMonths(3), saved.getPaidUntil());
    }

    @Test
    void assign_existingActiveSubscription_blocksCampaignBonusThroughEligibilityCheck() {
        UserSubscriptionService svc = serviceWithRealCampaignEligibility();

        when(packageRepository.findById(5L)).thenReturn(Optional.of(pkg));
        // Real eligibility chain: campaign active + 3->1 offer + not redeemed, but the
        // user already has an ACTIVE/FROZEN subscription -> bonus must be refused.
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                eq(CampaignStatus.ACTIVE), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Optional.of(campaign()));
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 3))
                .thenReturn(Optional.of(new CampaignOffer(10L, 1L, 3, 1)));
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(subscriptionRepository.existsByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN")))
                .thenReturn(true);
        Subscription existing = campaignSubscription(9L, null, null,
                LocalDateTime.of(2026, 12, 1, 0, 0), 0);
        // Must be mutable: assignSubscriptionToUser does toFinish.addAll(...) on the result.
        when(subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(100L, "ACTIVE"))
                .thenReturn(new java.util.ArrayList<>(List.of(existing)));
        stubSaveAssignsId(555L);

        AdminAssignSubscriptionResponse resp = svc.assignSubscriptionToUser(request(true, null));

        assertFalse(resp.campaignApplied());
        assertEquals(0, resp.bonusMonths());
        assertNull(resp.campaignId());
        // The eligibility check above ran with NEW_PURCHASE and was blocked by the existing sub.
        verify(subscriptionRepository).existsByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN"));
        verify(campaignOfferRepository).findByCampaignIdAndBaseDurationMonths(1L, 3);
        // No redemption, and the previous subscription was finished instead.
        verify(userCampaignRedemptionRepository, never()).save(any());
        assertEquals("FINISHED", existing.getStatus());

        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository, atLeastOnce()).save(subCaptor.capture());
        Subscription assigned = subCaptor.getAllValues().get(subCaptor.getAllValues().size() - 1);
        assertEquals(0, assigned.getBonusMonths());
        assertNull(assigned.getCampaignId());
        assertEquals(assigned.getStartAt().plusMonths(3), assigned.getEndAt());
    }

    @Test
    void assign_redemptionUniqueConstraintRace_bonusZeroedWithoutPropagatingException() {
        when(campaignEligibilityService.getBakuZone()).thenReturn(BAKU);
        when(packageRepository.findById(5L)).thenReturn(Optional.of(pkg));
        when(campaignEligibilityService.resolve(eq(100L), eq(5L), eq(3),
                eq(PurchaseKind.NEW_PURCHASE), any(LocalDateTime.class)))
                .thenReturn(eligibleDecision(3, 1));
        stubSaveAssignsId(555L);
        when(userCampaignRedemptionRepository.save(any(UserCampaignRedemption.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key user_campaign_redemptions"));

        AdminAssignSubscriptionResponse resp = assertDoesNotThrow(
                () -> service.assignSubscriptionToUser(request(true, null)));

        // Race fallback: bonus rolled back, no exception leaks to the caller
        assertFalse(resp.campaignApplied());
        assertEquals(0, resp.bonusMonths());
        assertNull(resp.campaignId());

        // Subscription saved twice (initial + fallback re-save) with final state bonus=0
        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository, times(2)).save(subCaptor.capture());
        Subscription saved = subCaptor.getValue();
        assertEquals(0, saved.getBonusMonths());
        assertNull(saved.getCampaignId());
        assertEquals(saved.getPaidUntil(), saved.getEndAt(), "endAt rolled back to paidUntil");
        assertEquals(saved.getStartAt().plusMonths(3), saved.getPaidUntil());
        assertEquals(3, saved.getPaidDurationMonths());
        verify(userCampaignRedemptionRepository, times(1)).save(any(UserCampaignRedemption.class));
        verify(subscriptionEventPublisher).publishSubscriptionEvent(100L, "ASSIGNED", 555L);
    }

    @Test
    void assign_autoPaymentEnabledWithMultiMonthDuration_throwsBadRequestBeforeSave() {
        when(campaignEligibilityService.getBakuZone()).thenReturn(BAKU);
        when(packageRepository.findById(5L)).thenReturn(Optional.of(pkg)); // option = 3 months

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.assignSubscriptionToUser(request(null, true)));

        assertEquals("error.auto_payment_only_for_1_month", ex.getMessage());
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verifyNoInteractions(userCampaignRedemptionRepository);
    }

    @Test
    void assign_autoPaymentEnabledWithOneMonthDuration_allowed() {
        when(campaignEligibilityService.getBakuZone()).thenReturn(BAKU);
        when(packageRepository.findById(5L)).thenReturn(Optional.of(pkgWithOption(7L, 1)));
        stubSaveAssignsId(556L);

        AdminAssignSubscriptionResponse resp = service.assignSubscriptionToUser(request(null, true));

        assertEquals("ACTIVE", resp.status());
        assertEquals(1, resp.durationMonths());
        assertFalse(resp.campaignApplied());

        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(subCaptor.capture());
        assertEquals(Boolean.TRUE, subCaptor.getValue().getAutoPaymentEnabled());
        assertEquals(subCaptor.getValue().getStartAt().plusMonths(1),
                subCaptor.getValue().getPaidUntil());
    }

    // ------------------------------------------------------------------
    // revokeCampaignBonusForRefund — branch A (subscriptionId given)
    // ------------------------------------------------------------------

    @Test
    void revoke_branchA_withPaidUntil_deletesRedemptionAndClampsEndAtToPaidUntil() {
        LocalDateTime paidUntil = LocalDateTime.of(2027, 1, 15, 0, 0);
        Subscription sub = campaignSubscription(10L, 1L, paidUntil,
                LocalDateTime.of(2027, 2, 15, 0, 0), 1);
        when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(sub));

        service.revokeCampaignBonusForRefund(10L, null);

        verify(userCampaignRedemptionRepository).deleteBySubscriptionId(10L);
        assertEquals(paidUntil, sub.getEndAt());
        assertEquals(0, sub.getBonusMonths());
        assertNull(sub.getCampaignId());
        verify(subscriptionRepository).save(sub);
        // Branch A never falls through to the per-user query
        verify(subscriptionRepository, never()).findByUserIdAndStatusIn(any(), any());
    }

    @Test
    void revoke_branchA_withNullPaidUntil_endAtLeftUnchanged() {
        LocalDateTime endAt = LocalDateTime.of(2027, 2, 15, 0, 0);
        Subscription sub = campaignSubscription(10L, 1L, null, endAt, 1);
        when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(sub));

        service.revokeCampaignBonusForRefund(10L, null);

        verify(userCampaignRedemptionRepository).deleteBySubscriptionId(10L);
        assertEquals(endAt, sub.getEndAt(), "paidUntil null -> endAt untouched");
        assertEquals(0, sub.getBonusMonths());
        assertNull(sub.getCampaignId());
        verify(subscriptionRepository).save(sub);
    }

    @Test
    void revoke_branchA_unknownSubscriptionId_isNoOp() {
        when(subscriptionRepository.findById(99L)).thenReturn(Optional.empty());

        service.revokeCampaignBonusForRefund(99L, null);

        verify(subscriptionRepository).findById(99L);
        verifyNoInteractions(userCampaignRedemptionRepository);
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    void revoke_branchA_subscriptionWithoutCampaign_stillDeletesAndSaves() {
        // Documents current behaviour: branch A does NOT check campaignId; it rewrites every
        // subscription passed to it (harmless for no-bonus rows, but the write still happens).
        LocalDateTime paidUntil = LocalDateTime.of(2027, 1, 15, 0, 0);
        LocalDateTime endAt = LocalDateTime.of(2027, 1, 15, 0, 0);
        Subscription sub = campaignSubscription(11L, null, paidUntil, endAt, 0);
        when(subscriptionRepository.findById(11L)).thenReturn(Optional.of(sub));

        service.revokeCampaignBonusForRefund(11L, null);

        verify(userCampaignRedemptionRepository).deleteBySubscriptionId(11L);
        verify(subscriptionRepository).save(sub);
        assertEquals(paidUntil, sub.getEndAt());
        assertEquals(0, sub.getBonusMonths());
        assertNull(sub.getCampaignId());
    }

    // ------------------------------------------------------------------
    // revokeCampaignBonusForRefund — branch B (only userId given)
    // ------------------------------------------------------------------

    @Test
    void revoke_branchB_onlyCampaignSubscriptionsAreTouched() {
        Subscription withCampaign = campaignSubscription(10L, 1L, LocalDateTime.of(2027, 1, 15, 0, 0),
                LocalDateTime.of(2027, 2, 15, 0, 0), 1);
        Subscription withoutCampaign = campaignSubscription(11L, null, LocalDateTime.of(2026, 12, 1, 0, 0),
                LocalDateTime.of(2026, 12, 1, 0, 0), 0);
        when(subscriptionRepository.findByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN", "FINISHED")))
                .thenReturn(List.of(withCampaign, withoutCampaign));

        service.revokeCampaignBonusForRefund(null, 100L);

        verify(userCampaignRedemptionRepository).deleteBySubscriptionId(10L);
        verify(userCampaignRedemptionRepository, never()).deleteBySubscriptionId(11L);
        verify(subscriptionRepository).save(withCampaign);
        verify(subscriptionRepository, never()).save(withoutCampaign);
        verify(subscriptionRepository, never()).findById(anyLong());

        assertEquals(LocalDateTime.of(2027, 1, 15, 0, 0), withCampaign.getEndAt());
        assertEquals(0, withCampaign.getBonusMonths());
        assertNull(withCampaign.getCampaignId());
        // Non-campaign row untouched
        assertEquals(LocalDateTime.of(2026, 12, 1, 0, 0), withoutCampaign.getEndAt());
        assertNull(withoutCampaign.getCampaignId());
    }

    // ------------------------------------------------------------------
    // Id fall-through
    // ------------------------------------------------------------------

    @Test
    void revoke_noIdentifiers_isNoOp() {
        service.revokeCampaignBonusForRefund(null, null);

        verifyNoInteractions(subscriptionRepository, userCampaignRedemptionRepository);
    }

    @Test
    void revoke_zeroSubscriptionIdWithUserId_fallsThroughToBranchB() {
        Subscription withCampaign = campaignSubscription(10L, 1L, LocalDateTime.of(2027, 1, 15, 0, 0),
                LocalDateTime.of(2027, 2, 15, 0, 0), 1);
        when(subscriptionRepository.findByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN", "FINISHED")))
                .thenReturn(List.of(withCampaign));

        // subscriptionId = 0 fails the > 0 guard, so the userId branch runs instead
        service.revokeCampaignBonusForRefund(0L, 100L);

        verify(subscriptionRepository, never()).findById(any());
        verify(userCampaignRedemptionRepository).deleteBySubscriptionId(10L);
        assertEquals(0, withCampaign.getBonusMonths());
    }

    // ------------------------------------------------------------------

    private Campaign campaign() {
        Campaign c = new Campaign();
        c.setId(1L);
        c.setCode("OCT_2026");
        c.setStatus(CampaignStatus.ACTIVE);
        c.setStartAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        c.setEndAt(LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        c.setBannerImageUrl("banner.png");
        c.setCtaTarget("CAMPAIGN_DETAIL");
        return c;
    }
}
