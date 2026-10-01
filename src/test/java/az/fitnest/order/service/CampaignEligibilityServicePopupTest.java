package az.fitnest.order.service;

import az.fitnest.order.dto.ActiveCampaignResponseDto;
import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Behaviour tests for {@link CampaignEligibilityService#popup} — eligibility, the
 * per-user daily POPUP cap and the {@code nextEligibleShowAt} computation.
 */
@ExtendWith(MockitoExtension.class)
class CampaignEligibilityServicePopupTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 14, 0, 0);

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
        campaign.setStartAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        campaign.setEndAt(LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        campaign.setBannerImageUrl("https://img.fitnest.az/banner.png");
        campaign.setCtaTarget("CAMPAIGN_DETAIL");
    }

    private void stubActiveCampaign(LocalDateTime now) {
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                .thenReturn(Optional.of(campaign));
    }

    // ------------------------------------------------------------------
    // Eligibility branches
    // ------------------------------------------------------------------

    @Test
    void popup_noActiveCampaign_returnsAllFalseAndNullCampaign() {
        when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                eq(CampaignStatus.ACTIVE), eq(NOW), eq(NOW)))
                .thenReturn(Optional.empty());

        ActiveCampaignResponseDto response = service.popup(100L, "popup", "AZ", NOW);

        assertFalse(response.isEligible());
        assertFalse(response.isShowPopup());
        assertNull(response.getNextEligibleShowAt());
        assertNull(response.getCampaign());
        verifyNoInteractions(userCampaignRedemptionRepository, campaignImpressionRepository);
    }

    @Test
    void popup_userAlreadyRedeemed_returnsNotEligibleWithNulledCampaign() {
        stubActiveCampaign(NOW);
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(true);

        ActiveCampaignResponseDto response = service.popup(100L, "popup", "AZ", NOW);

        assertFalse(response.isEligible());
        assertFalse(response.isShowPopup());
        assertNull(response.getNextEligibleShowAt());
        assertNull(response.getCampaign());
        // Redemption short-circuits before the daily cap is ever consulted.
        verifyNoInteractions(campaignImpressionRepository);
    }

    @Test
    void popup_firstShow_noPriorImpression_showsPopup() {
        stubActiveCampaign(NOW);
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.empty());

        ActiveCampaignResponseDto response = service.popup(100L, "popup", "AZ", NOW);

        assertTrue(response.isEligible());
        assertTrue(response.isShowPopup());
        assertNull(response.getNextEligibleShowAt());
        assertNotNull(response.getCampaign());
        assertEquals("OCT_2026", response.getCampaign().getCode());
    }

    // ------------------------------------------------------------------
    // Daily cap
    // ------------------------------------------------------------------

    @Test
    void popup_impressionEarlierToday_capBlocksSecondShowUntilTomorrowMidnight() {
        stubActiveCampaign(NOW);
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.of(new CampaignImpression(7L, 100L, 1L, "POPUP",
                        LocalDateTime.of(2026, 10, 15, 9, 30))));

        ActiveCampaignResponseDto response = service.popup(100L, "popup", "AZ", NOW);

        assertTrue(response.isEligible());
        assertFalse(response.isShowPopup());
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), response.getNextEligibleShowAt());
        // Campaign payload is still returned even when the popup is suppressed.
        assertNotNull(response.getCampaign());
        assertEquals(1L, response.getCampaign().getId());
    }

    @Test
    void popup_impressionYesterday_capAllowsShowAgain() {
        stubActiveCampaign(NOW);
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.of(new CampaignImpression(7L, 100L, 1L, "POPUP",
                        LocalDateTime.of(2026, 10, 14, 23, 59))));

        ActiveCampaignResponseDto response = service.popup(100L, "popup", "AZ", NOW);

        assertTrue(response.isEligible());
        assertTrue(response.isShowPopup());
        assertNull(response.getNextEligibleShowAt());
    }

    @Test
    void popup_impressionTimestampInFuture_clockSkewAlsoBlocksShow() {
        stubActiveCampaign(NOW);
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        // Documented behaviour: the guard is !lastShown.toLocalDate().isBefore(today), so a
        // future-dated impression (clock skew) is treated as "already shown today".
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.of(new CampaignImpression(7L, 100L, 1L, "POPUP",
                        LocalDateTime.of(2026, 10, 16, 10, 0))));

        ActiveCampaignResponseDto response = service.popup(100L, "popup", "AZ", NOW);

        assertTrue(response.isEligible());
        assertFalse(response.isShowPopup());
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), response.getNextEligibleShowAt());
        assertNotNull(response.getCampaign());
    }

    @Test
    void popup_capCrossesMonthBoundary_nextEligibleShowAtIsNovemberFirstMidnight() {
        LocalDateTime oct31 = LocalDateTime.of(2026, 10, 31, 18, 0);
        stubActiveCampaign(oct31);
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.of(new CampaignImpression(7L, 100L, 1L, "POPUP",
                        LocalDateTime.of(2026, 10, 31, 8, 0))));

        ActiveCampaignResponseDto response = service.popup(100L, "popup", "AZ", oct31);

        assertFalse(response.isShowPopup());
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), response.getNextEligibleShowAt());
    }

    // ------------------------------------------------------------------
    // userId / context handling
    // ------------------------------------------------------------------

    @Test
    void popup_nullUserId_dailyCapSkipped() {
        stubActiveCampaign(NOW);

        ActiveCampaignResponseDto response = service.popup(null, "popup", "AZ", NOW);

        assertTrue(response.isEligible());
        assertTrue(response.isShowPopup());
        assertNull(response.getNextEligibleShowAt());
        assertNotNull(response.getCampaign());
        // Anonymous traffic never consults per-user state.
        verifyNoInteractions(userCampaignRedemptionRepository, campaignImpressionRepository);
    }

    @Test
    void popup_nonPopupContexts_capSkipped_bannerBannerAndUnknown() {
        for (String context : new String[]{"banner", "BANNER", "xyz", null}) {
            LocalDateTime now = NOW;
            Campaign c = campaign;
            when(campaignRepository.findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                    eq(CampaignStatus.ACTIVE), eq(now), eq(now)))
                    .thenReturn(Optional.of(c));

            ActiveCampaignResponseDto response = service.popup(100L, context, "AZ", now);

            assertTrue(response.isEligible(), "eligible for context " + context);
            assertTrue(response.isShowPopup(), "showPopup for context " + context);
            assertNull(response.getNextEligibleShowAt());
        }
        // Only "popup" (case-insensitive) is subject to the daily cap...
        verify(campaignImpressionRepository, never())
                .findByUserIdAndCampaignIdAndContext(anyLong(), anyLong(), anyString());
        // ...while the redemption short-circuit still runs for every non-null userId.
        verify(userCampaignRedemptionRepository, times(4))
                .existsByUserIdAndCampaignId(100L, 1L);
    }

    @Test
    void popup_contextPopUpVariants_capAppliesCaseInsensitively() {
        for (String context : new String[]{"POPUP", "popup", "PopUp"}) {
            stubActiveCampaign(NOW);
            when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
            when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                    .thenReturn(Optional.of(new CampaignImpression(7L, 100L, 1L, "POPUP",
                            LocalDateTime.of(2026, 10, 15, 9, 0))));

            ActiveCampaignResponseDto response = service.popup(100L, context, "AZ", NOW);

            assertTrue(response.isEligible(), "eligible for context " + context);
            assertFalse(response.isShowPopup(), "cap must apply for context " + context);
            assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), response.getNextEligibleShowAt());
            assertNotNull(response.getCampaign(), "campaign present while suppressed for " + context);
        }
        verify(campaignImpressionRepository, times(3))
                .findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP");
    }

    // ------------------------------------------------------------------
    // Detail payload
    // ------------------------------------------------------------------

    @Test
    void popup_returnsFullCampaignDetailPayload() {
        stubActiveCampaign(NOW);
        when(userCampaignRedemptionRepository.existsByUserIdAndCampaignId(100L, 1L)).thenReturn(false);
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.empty());
        when(campaignOfferRepository.findByCampaignId(1L))
                .thenReturn(java.util.List.of(new CampaignOffer(10L, 1L, 3, 1)));
        when(campaignTermRepository.findByCampaignIdOrderBySortOrderAsc(1L))
                .thenReturn(java.util.List.of(new CampaignTerm(50L, 1L, 1, true)));
        when(translationService.getTranslatedValue("CAMPAIGN", "1", "title", "EN"))
                .thenReturn("October title");
        when(translationService.getTranslatedValue("CAMPAIGNOFFER", "10", "label", "EN"))
                .thenReturn("3+1 ay");

        ActiveCampaignResponseDto response = service.popup(100L, "popup", "EN", NOW);

        CampaignDetailDto detail = response.getCampaign();
        assertNotNull(detail);
        assertEquals(1L, detail.getId());
        assertEquals("OCT_2026", detail.getCode());
        assertEquals("October title", detail.getTitle());
        assertEquals("https://img.fitnest.az/banner.png", detail.getBannerImageUrl());
        assertEquals("CAMPAIGN_DETAIL", detail.getCtaTarget());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), detail.getStartAt());
        assertEquals(LocalDateTime.of(2026, 10, 31, 23, 59, 59), detail.getEndAt());
        assertEquals(1, detail.getOffers().size());
        assertEquals(4, detail.getOffers().get(0).getTotalMonths());
        assertEquals(1, detail.getTerms().size());
        assertEquals(Boolean.TRUE, detail.getTerms().get(0).getIsPositive());
    }
}
