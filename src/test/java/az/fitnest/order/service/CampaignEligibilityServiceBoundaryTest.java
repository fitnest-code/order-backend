package az.fitnest.order.service;

import az.fitnest.order.dto.BonusDecision;
import az.fitnest.order.dto.PurchaseKind;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Boundary / edge-case tests for {@link CampaignEligibilityService#resolve}.
 *
 * <p>The repository window query is inclusive: {@code start_at <= now <= end_at},
 * so the exact boundary instants must yield an offer while one second outside must not.
 */
@ExtendWith(MockitoExtension.class)
class CampaignEligibilityServiceBoundaryTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 31, 23, 59, 59);

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignOfferRepository campaignOfferRepository;
    @Mock
    private CampaignTermRepository campaignTermRepository;
    @Mock
    private UserCampaignRedemptionRepository userCampaignRedemptionRepository;
    @Mock
    private CampaignImpressionRepository campaignImpressionRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private TranslationService translationService;

    @InjectMocks
    private CampaignEligibilityService service;

    private Campaign campaign;

    @BeforeEach
    void setUp() {
        campaign = new Campaign();
        campaign.setId(1L);
        campaign.setCode("OCT_2026");
        campaign.setStatus(CampaignStatus.ACTIVE);
        campaign.setStartAt(START);
        campaign.setEndAt(END);
        campaign.setBannerImageUrl("https://img.fitnest.az/banner.png");
        campaign.setCtaTarget("CAMPAIGN_DETAIL");
    }

    private void stubActiveCampaign(LocalDateTime now) {
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.of(campaign));
    }

    private void stubNoCampaign(LocalDateTime now) {
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.empty());
    }

    private void stubFullyEligible(LocalDateTime now, int baseMonths, int bonusMonths) {
        stubActiveCampaign(now);
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, baseMonths))
                .thenReturn(Optional.of(new CampaignOffer(10L, 1L, baseMonths, bonusMonths)));
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(subscriptionRepository.existsByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN"))).thenReturn(false);
        when(translationService.getTranslatedValue("CAMPAIGNOFFER", "10", "label", "AZ")).thenReturn("3+1 ay");
    }

    // ------------------------------------------------------------------
    // Inclusive window boundaries
    // ------------------------------------------------------------------

    @Test
    void resolve_nowExactlyAtStartAt_campaignApplied() {
        stubFullyEligible(START, 3, 1);

        BonusDecision decision = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, START);

        assertTrue(decision.isApplied());
        assertEquals(1L, decision.getCampaignId());
        assertEquals(4, decision.getTotalMonths());
    }

    @Test
    void resolve_nowExactlyAtEndAt_campaignApplied() {
        stubFullyEligible(END, 3, 1);

        BonusDecision decision = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, END);

        assertTrue(decision.isApplied());
        assertEquals(1L, decision.getCampaignId());
        assertEquals(4, decision.getTotalMonths());
    }

    @Test
    void resolve_oneSecondAfterEndAt_campaignNotApplied() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 1, 0, 0, 0);
        stubNoCampaign(now);

        BonusDecision decision = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);

        assertFalse(decision.isApplied());
        assertNull(decision.getCampaignId());
        assertEquals(3, decision.getPaidMonths());
        assertEquals(0, decision.getBonusMonths());
        // The closed window ends at 2026-10-31T23:59:59; 2026-11-01T00:00:00 is outside.
        assertEquals(3, decision.getTotalMonths());
    }

    @Test
    void resolve_oneSecondBeforeStartAt_campaignNotApplied() {
        LocalDateTime now = START.minusSeconds(1);
        stubNoCampaign(now);

        BonusDecision decision = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);

        assertFalse(decision.isApplied());
        assertEquals(3, decision.getPaidMonths());
        assertEquals(0, decision.getBonusMonths());
    }

    // ------------------------------------------------------------------
    // Input nullability / degenerate inputs
    // ------------------------------------------------------------------

    @Test
    void resolve_nullDuration_returnsNotAppliedWithZeroPaidMonths() {
        BonusDecision decision = service.resolve(100L, 1L, null, PurchaseKind.NEW_PURCHASE,
                LocalDateTime.of(2026, 10, 15, 12, 0));

        assertFalse(decision.isApplied());
        // Documented oddity: paidMonths is hard-coded to 0 rather than to the (null) input.
        assertEquals(0, decision.getPaidMonths());
        assertEquals(0, decision.getBonusMonths());
        assertEquals(0, decision.getTotalMonths());
        verifyNoInteractions(campaignRepository, campaignOfferRepository,
                userCampaignRedemptionRepository, subscriptionRepository);
    }

    @Test
    void resolve_nullUserId_skipsRedemptionAndSubscriptionExistenceChecks() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        stubActiveCampaign(now);
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 3))
                .thenReturn(Optional.of(new CampaignOffer(10L, 1L, 3, 1)));
        when(translationService.getTranslatedValue("CAMPAIGNOFFER", "10", "label", "AZ")).thenReturn("3+1 ay");

        BonusDecision decision = service.resolve(null, 1L, 3, PurchaseKind.NEW_PURCHASE, now);

        assertTrue(decision.isApplied());
        assertEquals(1L, decision.getCampaignId());
        // Anonymous / system resolution must never hit the per-user tables.
        verifyNoInteractions(userCampaignRedemptionRepository, subscriptionRepository);
    }

    @Test
    void resolve_nullPurchaseKind_treatedAsNotNewPurchase_withoutTouchingRepositories() {
        BonusDecision decision = service.resolve(100L, 1L, 3, null,
                LocalDateTime.of(2026, 10, 15, 12, 0));

        assertFalse(decision.isApplied());
        assertEquals(3, decision.getPaidMonths());
        assertEquals(0, decision.getBonusMonths());
        verifyNoInteractions(campaignRepository, campaignOfferRepository,
                userCampaignRedemptionRepository, subscriptionRepository);
    }

    @Test
    void resolve_nullNow_fallsBackToRealBakuClock() {
        // The whole chain is driven by "now", so pass matchers instead of a fixed value.
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                eq(CampaignStatus.ACTIVE), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Optional.of(campaign));
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 3))
                .thenReturn(Optional.of(new CampaignOffer(10L, 1L, 3, 1)));
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(subscriptionRepository.existsByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN"))).thenReturn(false);
        when(translationService.getTranslatedValue("CAMPAIGNOFFER", "10", "label", "AZ")).thenReturn("3+1 ay");

        BonusDecision decision = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, null);

        assertTrue(decision.isApplied());
        assertEquals(4, decision.getTotalMonths());
        assertEquals(ZoneId.of("Asia/Baku"), service.getBakuZone());

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(campaignRepository)
                .findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                        eq(CampaignStatus.ACTIVE), captor.capture(), captor.capture());
        List<LocalDateTime> queryArgs = captor.getAllValues();
        LocalDateTime passedNow = queryArgs.get(0);
        // Both window bounds are the same instant and it is a live Baku-clock reading.
        assertEquals(passedNow, queryArgs.get(1));
        LocalDateTime realBakuNow = LocalDateTime.now(ZoneId.of("Asia/Baku"));
        assertFalse(passedNow.isBefore(realBakuNow.minusMinutes(5)));
        assertFalse(passedNow.isAfter(realBakuNow.plusMinutes(5)));
    }

    // ------------------------------------------------------------------
    // Non-ACTIVE campaigns simply produce an empty repository result
    // ------------------------------------------------------------------

    @Test
    void resolve_draftExpiredOrDisabledCampaign_windowQueryIsEmpty_campaignNotApplied() {
        LocalDateTime[] clocks = {
                LocalDateTime.of(2026, 10, 2, 9, 0),
                LocalDateTime.of(2026, 10, 3, 9, 0),
                LocalDateTime.of(2026, 10, 4, 9, 0)
        };
        // DRAFT / EXPIRED / DISABLED are filtered by the repository query itself (status=ACTIVE),
        // so from the service's perspective the result is simply empty.
        stubNoCampaign(clocks[0]);
        stubNoCampaign(clocks[1]);
        stubNoCampaign(clocks[2]);

        for (LocalDateTime now : clocks) {
            BonusDecision decision = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);
            assertFalse(decision.isApplied(), "campaign must not apply at " + now);
            assertEquals(3, decision.getPaidMonths());
            assertEquals(0, decision.getBonusMonths());
        }
    }

    // ------------------------------------------------------------------
    // Offer matrix + passthrough of unusual data
    // ------------------------------------------------------------------

    @Test
    void resolve_threeSixTwelveMatrix_totalsAreFourEightFifteen() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        stubActiveCampaign(now);
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 3))
                .thenReturn(Optional.of(new CampaignOffer(10L, 1L, 3, 1)));
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 6))
                .thenReturn(Optional.of(new CampaignOffer(11L, 1L, 6, 2)));
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 12))
                .thenReturn(Optional.of(new CampaignOffer(12L, 1L, 12, 3)));
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(subscriptionRepository.existsByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN"))).thenReturn(false);
        when(translationService.getTranslatedValue(eq("CAMPAIGNOFFER"), anyString(), eq("label"), eq("AZ")))
                .thenReturn("label");

        BonusDecision d3 = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);
        BonusDecision d6 = service.resolve(100L, 1L, 6, PurchaseKind.NEW_PURCHASE, now);
        BonusDecision d12 = service.resolve(100L, 1L, 12, PurchaseKind.NEW_PURCHASE, now);

        assertEquals(3, d3.getPaidMonths());
        assertEquals(1, d3.getBonusMonths());
        assertEquals(4, d3.getTotalMonths());

        assertEquals(6, d6.getPaidMonths());
        assertEquals(2, d6.getBonusMonths());
        assertEquals(8, d6.getTotalMonths());

        assertEquals(12, d12.getPaidMonths());
        assertEquals(3, d12.getBonusMonths());
        assertEquals(15, d12.getTotalMonths());
    }

    @Test
    void resolve_offerWithUnusualBonus_passesThroughUncapped() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        stubActiveCampaign(now);
        // A misconfigured offer (bonus 5 for a 3-month base) is NOT capped by the service.
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 3))
                .thenReturn(Optional.of(new CampaignOffer(99L, 1L, 3, 5)));
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(subscriptionRepository.existsByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN"))).thenReturn(false);
        when(translationService.getTranslatedValue("CAMPAIGNOFFER", "99", "label", "AZ")).thenReturn("3+5 ay");

        BonusDecision decision = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);

        assertTrue(decision.isApplied());
        assertEquals(5, decision.getBonusMonths());
        assertEquals(8, decision.getTotalMonths());
    }

    // ------------------------------------------------------------------
    // campaignLabel translation fallback
    // ------------------------------------------------------------------

    @Test
    void resolve_campaignLabel_takenFromTranslationWhenPresent() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        stubFullyEligible(now, 3, 1);

        BonusDecision decision = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);

        assertTrue(decision.isApplied());
        assertEquals("3+1 ay", decision.getCampaignLabel());
    }

    @Test
    void resolve_campaignLabel_fallsBackToNullWhenTranslationBlankNullOrThrows() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        stubActiveCampaign(now);
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 3))
                .thenReturn(Optional.of(new CampaignOffer(10L, 1L, 3, 1)));
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(subscriptionRepository.existsByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN"))).thenReturn(false);
        // Consecutive answers: null -> blank -> whitespace -> exception.
        when(translationService.getTranslatedValue("CAMPAIGNOFFER", "10", "label", "AZ"))
                .thenReturn(null)
                .thenReturn("")
                .thenReturn("   ")
                .thenThrow(new RuntimeException("translation backend down"));

        assertNull(service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now).getCampaignLabel());
        assertNull(service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now).getCampaignLabel());
        assertNull(service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now).getCampaignLabel());
        BonusDecision decision = service.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);

        assertTrue(decision.isApplied(), "translation failure must not block the bonus");
        assertEquals(4, decision.getTotalMonths());
        assertNull(decision.getCampaignLabel());
    }
}
