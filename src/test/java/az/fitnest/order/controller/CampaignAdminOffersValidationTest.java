package az.fitnest.order.controller;

import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.dto.CampaignOfferDto;
import az.fitnest.order.dto.admin.AdminCampaignOfferRequest;
import az.fitnest.order.dto.admin.AdminCampaignTermRequest;
import az.fitnest.order.exception.GlobalExceptionHandler;
import az.fitnest.order.model.entity.Campaign;
import az.fitnest.order.model.entity.CampaignOffer;
import az.fitnest.order.model.entity.CampaignStatus;
import az.fitnest.order.model.entity.CampaignTerm;
import az.fitnest.order.repository.CampaignOfferRepository;
import az.fitnest.order.repository.CampaignRepository;
import az.fitnest.order.repository.CampaignTermRepository;
import az.fitnest.order.service.CampaignEligibilityService;
import az.fitnest.order.service.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc validation tests for the admin offer/term replacement endpoints of
 * {@link CampaignAdminController}.
 *
 * <p>The bonus matrix is enforced manually in the controller: base must be one of 3/6/12 and
 * the bonus must be 3→1, 6→2, 12→3, otherwise {@code BadRequestException} with the message keys
 * {@code error.invalid_base_duration_months} / {@code error.invalid_bonus_months} are raised and
 * mapped by the production {@link GlobalExceptionHandler} to HTTP 400.
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} is not asserted here: method security is a
 * bean-post-processing concern of the full Spring context and is inert under
 * {@code standaloneSetup}, so role checks cannot be exercised in this harness.
 */
@ExtendWith(MockitoExtension.class)
class CampaignAdminOffersValidationTest {

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignOfferRepository campaignOfferRepository;
    @Mock
    private CampaignTermRepository campaignTermRepository;
    @Mock
    private CampaignEligibilityService campaignEligibilityService;
    @Mock
    private TranslationService translationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CampaignAdminController controller = new CampaignAdminController(
                campaignRepository, campaignOfferRepository, campaignTermRepository,
                campaignEligibilityService, translationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    private Campaign octoberCampaign() {
        Campaign campaign = new Campaign();
        campaign.setId(1L);
        campaign.setCode("OCT_2026");
        campaign.setStatus(CampaignStatus.ACTIVE);
        campaign.setStartAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        campaign.setEndAt(LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        campaign.setCtaTarget("CAMPAIGN_DETAIL");
        return campaign;
    }

    private void stubCampaignFound() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(octoberCampaign()));
    }

    private void stubOffersSaveAssigningIds(long... ids) {
        AtomicInteger seq = new AtomicInteger();
        when(campaignOfferRepository.save(any(CampaignOffer.class))).thenAnswer(inv -> {
            CampaignOffer offer = inv.getArgument(0);
            offer.setId(ids[seq.getAndIncrement()]);
            return offer;
        });
    }

    private void stubTermsSaveAssigningIds(long... ids) {
        AtomicInteger seq = new AtomicInteger();
        when(campaignTermRepository.save(any(CampaignTerm.class))).thenAnswer(inv -> {
            CampaignTerm term = inv.getArgument(0);
            term.setId(ids[seq.getAndIncrement()]);
            return term;
        });
    }

    private CampaignDetailDto minimalDetail() {
        return CampaignDetailDto.builder()
                .id(1L)
                .code("OCT_2026")
                .title("Oktyabr")
                .offers(List.of(CampaignOfferDto.builder()
                        .baseDurationMonths(3).bonusMonths(1).totalMonths(4).build()))
                .terms(List.of())
                .build();
    }

    // ------------------------------------------------------------------
    // PUT /{id}/offers — base duration validation
    // ------------------------------------------------------------------

    @Test
    void updateOffers_baseOutsideThreeSixTwelve_rejectsWithInvalidBaseDurationKey() throws Exception {
        for (int badBase : new int[]{1, 2, 4, 60}) {
            stubCampaignFound();

            mockMvc.perform(put("/api/v1/admin/campaigns/1/offers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[{\"base_duration_months\":" + badBase + ",\"bonus_months\":3}]"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.status").value(400))
                    .andExpect(jsonPath("$.error.message").value("error.invalid_base_duration_months"));

            // Delete-then-insert: the delete runs BEFORE validation (production wraps this in
            // @Transactional, so a BadRequestException rolls the delete back).
            verify(campaignOfferRepository).deleteByCampaignId(1L);
            verify(campaignOfferRepository, never()).save(any(CampaignOffer.class));
            org.mockito.Mockito.clearInvocations(campaignRepository, campaignOfferRepository);
        }
    }

    @Test
    void updateOffers_baseNull_failsBeanValidationBeforeManualCheck_andIsMappedTo500() throws Exception {
        // ACTUAL behaviour (documented, not fixed): Spring 7 enforces the @NotNull on
        // base_duration_months during argument resolution and raises
        // HandlerMethodValidationException (a ResponseStatusException carrying HTTP 400).
        // GlobalExceptionHandler has no handler for it, so the RuntimeException advice maps
        // it to 500 / RUNTIME_EXCEPTION instead of 400 / VALIDATION_ERROR — and the
        // controller's own manual null check (error.invalid_base_duration_months) is
        // therefore unreachable through HTTP.
        mockMvc.perform(put("/api/v1/admin/campaigns/1/offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"bonus_months\":1}]"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.status").value(500))
                .andExpect(jsonPath("$.error.code").value("RUNTIME_EXCEPTION"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("error.internal_server_error")));

        verify(campaignOfferRepository, never()).save(any(CampaignOffer.class));
        verify(campaignOfferRepository, never()).deleteByCampaignId(any());
    }

    // ------------------------------------------------------------------
    // PUT /{id}/offers — bonus matrix validation
    // ------------------------------------------------------------------

    @Test
    void updateOffers_bonusOffMatrix_rejectsWithInvalidBonusKey() throws Exception {
        int[][] violations = {{3, 2}, {6, 1}, {12, 5}};
        for (int[] v : violations) {
            stubCampaignFound();

            mockMvc.perform(put("/api/v1/admin/campaigns/1/offers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[{\"base_duration_months\":" + v[0]
                                    + ",\"bonus_months\":" + v[1] + "}]"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.status").value(400))
                    .andExpect(jsonPath("$.error.message").value("error.invalid_bonus_months"));

            verify(campaignOfferRepository, never()).save(any(CampaignOffer.class));
            org.mockito.Mockito.clearInvocations(campaignRepository, campaignOfferRepository);
        }
    }

    @Test
    void updateOffers_bonusNull_failsBeanValidationBeforeManualCheck_andIsMappedTo500() throws Exception {
        // ACTUAL behaviour (documented, not fixed): the @NotNull on bonus_months trips during
        // argument resolution → HandlerMethodValidationException → unhandled → 500, so the
        // manual "error.invalid_bonus_months" null branch never runs over HTTP either.
        mockMvc.perform(put("/api/v1/admin/campaigns/1/offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"base_duration_months\":3,\"bonus_months\":null}]"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.status").value(500))
                .andExpect(jsonPath("$.error.code").value("RUNTIME_EXCEPTION"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("error.internal_server_error")));

        verify(campaignOfferRepository, never()).save(any(CampaignOffer.class));
        verify(campaignOfferRepository, never()).deleteByCampaignId(any());
    }

    // ------------------------------------------------------------------
    // PUT /{id}/offers — valid matrix
    // ------------------------------------------------------------------

    @Test
    void updateOffers_validMatrix_replacesAllOffersAndTranslatesLabels() throws Exception {
        stubCampaignFound();
        stubOffersSaveAssigningIds(10L, 11L, 12L);
        when(campaignEligibilityService.getCampaignDetail(1L, "az")).thenReturn(minimalDetail());

        mockMvc.perform(put("/api/v1/admin/campaigns/1/offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("["
                                + "{\"base_duration_months\":3,\"bonus_months\":1,\"label\":\"3+1 ay\"},"
                                + "{\"base_duration_months\":6,\"bonus_months\":2},"
                                + "{\"base_duration_months\":12,\"bonus_months\":3}"
                                + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OCT_2026"));

        // Delete first, then insert the three replacement rows.
        InOrder inOrder = inOrder(campaignOfferRepository);
        inOrder.verify(campaignOfferRepository).deleteByCampaignId(1L);
        // NOTE: Mockito's in-order verification returns the whole greedy chunk of matching
        // invocations when it differs from the wanted count, so three consecutive save(...)
        // calls must be requested as a chunk of exactly 3 (not as three separate times(1)).
        inOrder.verify(campaignOfferRepository, times(3)).save(any(CampaignOffer.class));

        ArgumentCaptor<CampaignOffer> captor = ArgumentCaptor.forClass(CampaignOffer.class);
        verify(campaignOfferRepository, times(3)).save(captor.capture());
        List<CampaignOffer> saved = captor.getAllValues();
        assertEqualsOffer(saved.get(0), 1L, 3, 1);
        assertEqualsOffer(saved.get(1), 1L, 6, 2);
        assertEqualsOffer(saved.get(2), 1L, 12, 3);

        // Only the offer carrying a label gets a translation entry.
        verify(translationService).saveTranslation("CAMPAIGNOFFER", "10", "AZ", "label", "3+1 ay");
        verify(translationService, times(1)).saveTranslation(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void updateOffers_unknownCampaignId_mapsTo500ViaHandler() throws Exception {
        when(campaignRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/admin/campaigns/99/offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"base_duration_months\":3,\"bonus_months\":1}]"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("RUNTIME_EXCEPTION"));

        verify(campaignOfferRepository, never()).deleteByCampaignId(any());
    }

    // ------------------------------------------------------------------
    // PUT /{id}/terms — bean validation
    // ------------------------------------------------------------------

    @Test
    void updateTerms_missingRequiredFields_failsBeanValidationAndIsMappedTo500() throws Exception {
        // ACTUAL behaviour (documented, not fixed): the @NotNull constraints on sort_order and
        // is_positive trip during argument resolution → HandlerMethodValidationException
        // (ResponseStatusException, HTTP 400 intended) → GlobalExceptionHandler has no handler
        // for it → 500 RUNTIME_EXCEPTION instead of the expected 400 VALIDATION_ERROR.
        mockMvc.perform(put("/api/v1/admin/campaigns/1/terms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"text\":\"only text, no sort_order or is_positive\"}]"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.status").value(500))
                .andExpect(jsonPath("$.error.code").value("RUNTIME_EXCEPTION"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("error.internal_server_error")));

        // The controller body never runs: no delete, no insert.
        verify(campaignTermRepository, never()).deleteByCampaignId(any());
        verify(campaignTermRepository, never()).save(any(CampaignTerm.class));
        verify(campaignRepository, never()).findById(any());
    }

    @Test
    void updateTerms_validPayload_replacesTermsAndTranslatesText() throws Exception {
        stubCampaignFound();
        stubTermsSaveAssigningIds(30L);
        when(campaignEligibilityService.getCampaignDetail(1L, "az")).thenReturn(minimalDetail());

        mockMvc.perform(put("/api/v1/admin/campaigns/1/terms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"sort_order\":1,\"is_positive\":true,\"text\":\"Kampaniya şərtləri\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OCT_2026"));

        InOrder inOrder = inOrder(campaignTermRepository);
        inOrder.verify(campaignTermRepository).deleteByCampaignId(1L);
        inOrder.verify(campaignTermRepository).save(any(CampaignTerm.class));

        ArgumentCaptor<CampaignTerm> captor = ArgumentCaptor.forClass(CampaignTerm.class);
        verify(campaignTermRepository).save(captor.capture());
        CampaignTerm saved = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(1L, saved.getCampaignId());
        org.junit.jupiter.api.Assertions.assertEquals(1, saved.getSortOrder());
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, saved.getIsPositive());

        verify(translationService).saveTranslation("CAMPAIGNTERM", "30", "AZ", "text", "Kampaniya şərtləri");
    }

    private static void assertEqualsOffer(CampaignOffer offer, long campaignId, int base, int bonus) {
        org.junit.jupiter.api.Assertions.assertEquals(campaignId, offer.getCampaignId());
        org.junit.jupiter.api.Assertions.assertEquals(base, offer.getBaseDurationMonths());
        org.junit.jupiter.api.Assertions.assertEquals(bonus, offer.getBonusMonths());
    }
}
