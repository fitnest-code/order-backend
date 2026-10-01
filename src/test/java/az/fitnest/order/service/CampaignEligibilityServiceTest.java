package az.fitnest.order.service;

import az.fitnest.order.dto.ActiveCampaignResponseDto;
import az.fitnest.order.dto.BonusDecision;
import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.dto.PurchaseKind;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignEligibilityServiceTest {

    private static final ZoneId BAKU_ZONE = ZoneId.of("Asia/Baku");

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
    private CampaignEligibilityService campaignEligibilityService;

    private Campaign activeCampaign;

    @BeforeEach
    void setUp() {
        activeCampaign = new Campaign();
        activeCampaign.setId(1L);
        activeCampaign.setCode("OCT_2026");
        activeCampaign.setStatus(CampaignStatus.ACTIVE);
        activeCampaign.setStartAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        activeCampaign.setEndAt(LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        activeCampaign.setBannerImageUrl("https://img.fitnest.az/banner.png");
        activeCampaign.setCtaTarget("CAMPAIGN_DETAIL");
    }

    @Test
    void testResolve_SuccessfulEligibility_3Months() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.of(activeCampaign));

        CampaignOffer offer3 = new CampaignOffer(10L, 1L, 3, 1);
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 3))
                .thenReturn(Optional.of(offer3));

        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(subscriptionRepository.existsByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN"))).thenReturn(false);
        when(translationService.getTranslatedValue("CAMPAIGNOFFER", "10", "label", "AZ")).thenReturn("3+1 ay");

        BonusDecision decision = campaignEligibilityService.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);

        assertTrue(decision.isApplied());
        assertEquals(1L, decision.getCampaignId());
        assertEquals(1, decision.getBonusMonths());
        assertEquals(3, decision.getPaidMonths());
        assertEquals(4, decision.getTotalMonths());
    }

    @Test
    void testResolve_OffersMapping_6and12Months() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.of(activeCampaign));

        CampaignOffer offer6 = new CampaignOffer(11L, 1L, 6, 2);
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 6))
                .thenReturn(Optional.of(offer6));

        BonusDecision decision6 = campaignEligibilityService.resolve(100L, 1L, 6, PurchaseKind.NEW_PURCHASE, now);
        assertTrue(decision6.isApplied());
        assertEquals(2, decision6.getBonusMonths());
        assertEquals(8, decision6.getTotalMonths());

        CampaignOffer offer12 = new CampaignOffer(12L, 1L, 12, 3);
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 12))
                .thenReturn(Optional.of(offer12));

        BonusDecision decision12 = campaignEligibilityService.resolve(100L, 1L, 12, PurchaseKind.NEW_PURCHASE, now);
        assertTrue(decision12.isApplied());
        assertEquals(3, decision12.getBonusMonths());
        assertEquals(15, decision12.getTotalMonths());
    }

    @Test
    void testResolve_1MonthOption_NotEligible() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);

        BonusDecision decision = campaignEligibilityService.resolve(100L, 1L, 1, PurchaseKind.NEW_PURCHASE, now);

        assertFalse(decision.isApplied());
        assertEquals(0, decision.getBonusMonths());
        assertEquals(1, decision.getPaidMonths());
    }

    @Test
    void testResolve_AutoRenewOrUpgrade_NotEligible() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);

        BonusDecision decisionRenew = campaignEligibilityService.resolve(100L, 1L, 3, PurchaseKind.AUTO_RENEW, now);
        assertFalse(decisionRenew.isApplied());

        BonusDecision decisionUpgrade = campaignEligibilityService.resolve(100L, 1L, 3, PurchaseKind.UPGRADE, now);
        assertFalse(decisionUpgrade.isApplied());
    }

    @Test
    void testResolve_AlreadyRedeemed_NotEligible() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.of(activeCampaign));

        CampaignOffer offer3 = new CampaignOffer(10L, 1L, 3, 1);
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 3))
                .thenReturn(Optional.of(offer3));

        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(true);

        BonusDecision decision = campaignEligibilityService.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);
        assertFalse(decision.isApplied());
        assertEquals(0, decision.getBonusMonths());
    }

    @Test
    void testResolve_UserHasActiveOrFrozenSubscription_NotEligible() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.of(activeCampaign));

        CampaignOffer offer3 = new CampaignOffer(10L, 1L, 3, 1);
        when(campaignOfferRepository.findByCampaignIdAndBaseDurationMonths(1L, 3))
                .thenReturn(Optional.of(offer3));

        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(subscriptionRepository.existsByUserIdAndStatusIn(100L, List.of("ACTIVE", "FROZEN"))).thenReturn(true);

        BonusDecision decision = campaignEligibilityService.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);
        assertFalse(decision.isApplied());
    }

    @Test
    void testResolve_ExpiredCampaign_NotEligible() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 1, 10, 0);
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.empty());

        BonusDecision decision = campaignEligibilityService.resolve(100L, 1L, 3, PurchaseKind.NEW_PURCHASE, now);
        assertFalse(decision.isApplied());
    }

    @Test
    void testPopup_FirstShow_EligibleAndShowPopupTrue() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 10, 0);
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.of(activeCampaign));

        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.empty());

        ActiveCampaignResponseDto response = campaignEligibilityService.popup(100L, "popup", "az", now);

        assertTrue(response.isEligible());
        assertTrue(response.isShowPopup());
        assertNull(response.getNextEligibleShowAt());
        assertNotNull(response.getCampaign());
    }

    @Test
    void testPopup_ShownToday_ShowPopupFalseWithNextMidnight() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 14, 0);
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.of(activeCampaign));

        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);

        CampaignImpression impression = new CampaignImpression(1L, 100L, 1L, "POPUP", LocalDateTime.of(2026, 10, 15, 9, 0));
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.of(impression));

        ActiveCampaignResponseDto response = campaignEligibilityService.popup(100L, "popup", "az", now);

        assertTrue(response.isEligible());
        assertFalse(response.isShowPopup());
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), response.getNextEligibleShowAt());
        assertNotNull(response.getCampaign());
    }

    @Test
    void testPopup_ContextBanner_IgnoresDailyCap() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 14, 0);
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.of(activeCampaign));

        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);

        ActiveCampaignResponseDto response = campaignEligibilityService.popup(100L, "banner", "az", now);

        assertTrue(response.isEligible());
        assertTrue(response.isShowPopup());
    }
}
