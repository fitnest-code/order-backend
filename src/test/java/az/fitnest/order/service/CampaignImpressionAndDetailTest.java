package az.fitnest.order.service;

import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.dto.CampaignOfferDto;
import az.fitnest.order.dto.CampaignTermDto;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CampaignEligibilityService#recordImpression},
 * {@link CampaignEligibilityService#getCampaignDetail} and
 * {@link CampaignEligibilityService#buildCampaignDetail}.
 */
@ExtendWith(MockitoExtension.class)
class CampaignImpressionAndDetailTest {

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
        campaign.setBannerImageUrl("banner.png");
        campaign.setCtaTarget("CAMPAIGN_DETAIL");
    }

    // ------------------------------------------------------------------
    // recordImpression
    // ------------------------------------------------------------------

    @Test
    void recordImpression_nullUserId_isNoOp() {
        service.recordImpression(null, 1L, "POPUP");

        verifyNoInteractions(campaignImpressionRepository);
    }

    @Test
    void recordImpression_nullCampaignId_isNoOp() {
        service.recordImpression(100L, null, "POPUP");

        verifyNoInteractions(campaignImpressionRepository);
    }

    @Test
    void recordImpression_nullContext_isNoOp() {
        service.recordImpression(100L, 1L, null);

        verifyNoInteractions(campaignImpressionRepository);
    }

    @Test
    void recordImpression_lowerCaseContext_uppercasedBeforeLookupAndSave() {
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.empty());

        service.recordImpression(100L, 1L, "popup");

        ArgumentCaptor<CampaignImpression> captor = ArgumentCaptor.forClass(CampaignImpression.class);
        verify(campaignImpressionRepository).save(captor.capture());
        CampaignImpression saved = captor.getValue();
        assertEquals("POPUP", saved.getContext());
        assertEquals(100L, saved.getUserId());
        assertEquals(1L, saved.getCampaignId());
        assertNotNull(saved.getLastShownAt());
        verify(campaignImpressionRepository).findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP");
    }

    @Test
    void recordImpression_existingRow_upsertedThroughSaveWithRefreshedTimestamp() {
        CampaignImpression existing = new CampaignImpression(7L, 100L, 1L, "POPUP",
                LocalDateTime.of(2026, 10, 1, 8, 0));
        when(campaignImpressionRepository.findByUserIdAndCampaignIdAndContext(100L, 1L, "POPUP"))
                .thenReturn(Optional.of(existing));

        service.recordImpression(100L, 1L, "POPUP");

        ArgumentCaptor<CampaignImpression> captor = ArgumentCaptor.forClass(CampaignImpression.class);
        verify(campaignImpressionRepository).save(captor.capture());
        CampaignImpression saved = captor.getValue();
        assertSame(existing, saved, "existing row must be reused, not re-created");
        assertEquals(7L, saved.getId());
        LocalDateTime realBakuNow = LocalDateTime.now(java.time.ZoneId.of("Asia/Baku"));
        assertFalse(saved.getLastShownAt().isEqual(LocalDateTime.of(2026, 10, 1, 8, 0)),
                "lastShownAt must be refreshed to now");
        assertFalse(saved.getLastShownAt().isBefore(realBakuNow.minusMinutes(5)));
        assertFalse(saved.getLastShownAt().isAfter(realBakuNow.plusMinutes(5)));
        // Exactly one save — upsert semantics.
        verify(campaignImpressionRepository, times(1)).save(any());
    }

    // ------------------------------------------------------------------
    // getCampaignDetail
    // ------------------------------------------------------------------

    @Test
    void getCampaignDetail_unknownId_throwsIllegalArgumentException() {
        when(campaignRepository.findById(999L)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getCampaignDetail(999L, "AZ"));

        assertTrue(ex.getMessage().contains("999"));
        assertTrue(ex.getMessage().contains("Campaign not found"));
    }

    @Test
    void getCampaignDetail_knownId_returnsBuiltDetail() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(campaignOfferRepository.findByCampaignId(1L)).thenReturn(List.of());
        when(campaignTermRepository.findByCampaignIdOrderBySortOrderAsc(1L)).thenReturn(List.of());

        CampaignDetailDto detail = service.getCampaignDetail(1L, "EN");

        assertEquals(1L, detail.getId());
        assertEquals("OCT_2026", detail.getCode());
        assertNotNull(detail.getOffers());
        assertNotNull(detail.getTerms());
    }

    // ------------------------------------------------------------------
    // buildCampaignDetail
    // ------------------------------------------------------------------

    @Test
    void buildCampaignDetail_offerTotalsAreBasePlusBonus() {
        when(campaignOfferRepository.findByCampaignId(1L)).thenReturn(List.of(
                new CampaignOffer(10L, 1L, 3, 1),
                new CampaignOffer(11L, 1L, 6, 2),
                new CampaignOffer(12L, 1L, 12, 3)));
        when(campaignTermRepository.findByCampaignIdOrderBySortOrderAsc(1L)).thenReturn(List.of());

        CampaignDetailDto detail = service.buildCampaignDetail(campaign, "AZ");

        assertEquals(3, detail.getOffers().size());
        assertEquals(4, detail.getOffers().get(0).getTotalMonths());
        assertEquals(8, detail.getOffers().get(1).getTotalMonths());
        assertEquals(15, detail.getOffers().get(2).getTotalMonths());
        for (CampaignOfferDto offer : detail.getOffers()) {
            assertEquals(offer.getBaseDurationMonths() + offer.getBonusMonths(), offer.getTotalMonths());
        }
    }

    @Test
    void buildCampaignDetail_termsPreserveRepositoryOrder() {
        when(campaignOfferRepository.findByCampaignId(1L)).thenReturn(List.of());
        // Repository contract: ordered by sort_order ASC — detail must keep that order.
        when(campaignTermRepository.findByCampaignIdOrderBySortOrderAsc(1L)).thenReturn(List.of(
                new CampaignTerm(30L, 1L, 1, true),
                new CampaignTerm(31L, 1L, 2, false),
                new CampaignTerm(32L, 1L, 3, true)));

        CampaignDetailDto detail = service.buildCampaignDetail(campaign, "AZ");

        assertEquals(3, detail.getTerms().size());
        assertEquals(1, detail.getTerms().get(0).getSortOrder());
        assertEquals(2, detail.getTerms().get(1).getSortOrder());
        assertEquals(3, detail.getTerms().get(2).getSortOrder());
        assertEquals(Boolean.TRUE, detail.getTerms().get(0).getIsPositive());
        assertEquals(Boolean.FALSE, detail.getTerms().get(1).getIsPositive());
    }

    @Test
    void buildCampaignDetail_fieldsComeFromCampaignEntity() {
        when(campaignOfferRepository.findByCampaignId(1L)).thenReturn(List.of());
        when(campaignTermRepository.findByCampaignIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(translationService.getTranslatedValue("CAMPAIGN", "1", "title", "EN")).thenReturn("Title EN");
        when(translationService.getTranslatedValue("CAMPAIGN", "1", "description", "EN")).thenReturn("Desc EN");
        when(translationService.getTranslatedValue("CAMPAIGN", "1", "ctaLabel", "EN")).thenReturn("CTA EN");

        CampaignDetailDto detail = service.buildCampaignDetail(campaign, "EN");

        assertEquals("Title EN", detail.getTitle());
        assertEquals("Desc EN", detail.getDescription());
        assertEquals("CTA EN", detail.getCtaLabel());
        assertEquals("banner.png", detail.getBannerImageUrl());
        assertEquals("CAMPAIGN_DETAIL", detail.getCtaTarget());
        assertEquals(campaign.getStartAt(), detail.getStartAt());
        assertEquals(campaign.getEndAt(), detail.getEndAt());
    }

    @Test
    void buildCampaignDetail_translationServiceThrows_usesHardcodedFallbacks() {
        when(campaignOfferRepository.findByCampaignId(1L)).thenReturn(List.of());
        when(campaignTermRepository.findByCampaignIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(translationService.getTranslatedValue(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("translation backend down"));

        CampaignDetailDto detail = service.buildCampaignDetail(campaign, "EN");

        assertEquals("Daha çox idman, daha çox imkan!", detail.getTitle());
        assertEquals("3, 6 və 12 aylıq abunəliklərdə əlavə ayları bizdən hədiyyə al!", detail.getDescription());
        assertEquals("Ətraflı Bax", detail.getCtaLabel());
    }

    @Test
    void buildCampaignDetail_nullLanguage_defaultsToAz() {
        when(campaignOfferRepository.findByCampaignId(1L)).thenReturn(List.of());
        when(campaignTermRepository.findByCampaignIdOrderBySortOrderAsc(1L)).thenReturn(List.of());
        when(translationService.getTranslatedValue("CAMPAIGN", "1", "title", "AZ")).thenReturn(null);

        CampaignDetailDto detail = service.buildCampaignDetail(campaign, null);

        // Language defaults to "AZ", and a null translation falls back to the hardcoded copy.
        assertEquals("Daha çox idman, daha çox imkan!", detail.getTitle());
        verify(translationService).getTranslatedValue("CAMPAIGN", "1", "title", "AZ");
    }

    @Test
    void buildCampaignDetail_offerAndTermLabelsFallBackToNullWhenTranslationMissing() {
        when(campaignOfferRepository.findByCampaignId(1L))
                .thenReturn(List.of(new CampaignOffer(10L, 1L, 3, 1)));
        when(campaignTermRepository.findByCampaignIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of(new CampaignTerm(30L, 1L, 1, true)));
        when(translationService.getTranslatedValue(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        CampaignDetailDto detail = service.buildCampaignDetail(campaign, "EN");

        CampaignTermDto term = detail.getTerms().get(0);
        assertNull(term.getText(), "no fallback text is configured for terms");
        assertEquals(Boolean.TRUE, term.getIsPositive());
        assertNull(detail.getOffers().get(0).getLabel(), "no fallback label is configured for offers");
        assertEquals(3, detail.getOffers().get(0).getBaseDurationMonths());
    }
}
