package az.fitnest.order.controller;

import az.fitnest.order.dto.ActiveCampaignResponseDto;
import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.dto.CampaignOfferDto;
import az.fitnest.order.dto.CampaignTermDto;
import az.fitnest.order.exception.GlobalExceptionHandler;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.repository.SubscriptionRepository;
import az.fitnest.order.service.CampaignEligibilityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link CampaignController}.
 *
 * <p>No Spring context is started: the controller is registered directly together with the
 * production {@link GlobalExceptionHandler} (except for one test proving the exception that
 * escapes when no advice is wired). Static {@code UserContext} state is controlled through
 * {@link SecurityContextHolder}; language resolution goes through the real request headers.
 */
@ExtendWith(MockitoExtension.class)
class CampaignControllerStandaloneTest {

    @Mock
    private CampaignEligibilityService campaignEligibilityService;
    @Mock
    private SubscriptionRepository subscriptionRepository;

    private CampaignController controller;
    /** Standalone MockMvc with the production exception advice wired in. */
    private MockMvc mockMvc;
    /** Standalone MockMvc without any exception advice. */
    private MockMvc mockMvcNoAdvice;

    private static final LocalDateTime BAKU_NOW = LocalDateTime.of(2026, 10, 15, 12, 0, 0);

    @BeforeEach
    void setUp() {
        controller = new CampaignController(campaignEligibilityService, subscriptionRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        mockMvcNoAdvice = MockMvcBuilders.standaloneSetup(controller).build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, "n/a", List.of()));
    }

    private CampaignDetailDto detailDto() {
        return CampaignDetailDto.builder()
                .id(1L)
                .code("OCT_2026")
                .title("Oktyabr kampaniyası")
                .description("3, 6 və 12 aylıq abunəliklərdə əlavə aylar")
                .bannerImageUrl("https://img.fitnest.az/banner.png")
                .ctaLabel("Ətraflı Bax")
                .ctaTarget("CAMPAIGN_DETAIL")
                .startAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .endAt(LocalDateTime.of(2026, 10, 31, 23, 59, 59))
                .offers(List.of(CampaignOfferDto.builder()
                        .baseDurationMonths(3).bonusMonths(1).totalMonths(4).label("3+1 ay").build()))
                .terms(List.of(CampaignTermDto.builder()
                        .text("Kampaniya şərtləri").isPositive(true).sortOrder(1).build()))
                .build();
    }

    private ActiveCampaignResponseDto emptyResponse() {
        return ActiveCampaignResponseDto.builder()
                .eligible(false).showPopup(false).nextEligibleShowAt(null).campaign(null).build();
    }

    // ------------------------------------------------------------------
    // GET /api/v1/campaigns/active
    // ------------------------------------------------------------------

    @Test
    void getActiveCampaign_noCampaign_returns200WithAllFalsePayload_never404() throws Exception {
        when(campaignEligibilityService.popup(isNull(), eq("popup"), eq("AZ"), isNull()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/campaigns/active").header("Accept-Language", "az"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.showPopup").value(false))
                .andExpect(jsonPath("$.nextEligibleShowAt").value(nullValue()))
                .andExpect(jsonPath("$.campaign").value(nullValue()));

        verify(campaignEligibilityService).popup(isNull(), eq("popup"), eq("AZ"), isNull());
    }

    @Test
    void getActiveCampaign_withCampaign_returns200EligibleTrueWithFullPayload() throws Exception {
        when(campaignEligibilityService.popup(isNull(), eq("popup"), eq("AZ"), isNull()))
                .thenReturn(ActiveCampaignResponseDto.builder()
                        .eligible(true).showPopup(true).nextEligibleShowAt(null)
                        .campaign(detailDto()).build());

        mockMvc.perform(get("/api/v1/campaigns/active").header("Accept-Language", "az"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.showPopup").value(true))
                .andExpect(jsonPath("$.campaign.code").value("OCT_2026"))
                .andExpect(jsonPath("$.campaign.ctaTarget").value("CAMPAIGN_DETAIL"))
                .andExpect(jsonPath("$.campaign.offers[0].baseDurationMonths").value(3))
                .andExpect(jsonPath("$.campaign.offers[0].bonusMonths").value(1))
                .andExpect(jsonPath("$.campaign.offers[0].totalMonths").value(4))
                .andExpect(jsonPath("$.campaign.offers[0].label").value("3+1 ay"))
                // CampaignTermDto serializes isPositive under the "positive" key
                .andExpect(jsonPath("$.campaign.terms[0].positive").value(true))
                .andExpect(jsonPath("$.campaign.terms[0].sortOrder").value(1))
                .andExpect(jsonPath("$.campaign.terms[0].text").value("Kampaniya şərtləri"))
                .andExpect(jsonPath("$.campaign.startAt").value("2026-10-01T00:00:00"))
                .andExpect(jsonPath("$.campaign.endAt").value("2026-10-31T23:59:59"));
    }

    @Test
    void getActiveCampaign_suppressedPopup_serializesNextEligibleShowAt() throws Exception {
        when(campaignEligibilityService.popup(isNull(), eq("popup"), eq("AZ"), isNull()))
                .thenReturn(ActiveCampaignResponseDto.builder()
                        .eligible(true).showPopup(false)
                        .nextEligibleShowAt(LocalDateTime.of(2026, 10, 16, 0, 0))
                        .campaign(detailDto()).build());

        mockMvc.perform(get("/api/v1/campaigns/active").header("Accept-Language", "az"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.showPopup").value(false))
                .andExpect(jsonPath("$.nextEligibleShowAt").value("2026-10-16T00:00:00"));
    }

    @Test
    void getActiveCampaign_contextDefaultsToPopupAndExplicitContextIsPassedThrough() throws Exception {
        when(campaignEligibilityService.popup(isNull(), eq("popup"), eq("AZ"), isNull()))
                .thenReturn(emptyResponse());
        when(campaignEligibilityService.popup(isNull(), eq("banner"), eq("AZ"), isNull()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/campaigns/active").header("Accept-Language", "az"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/campaigns/active")
                        .param("context", "banner")
                        .header("Accept-Language", "az"))
                .andExpect(status().isOk());

        verify(campaignEligibilityService).popup(isNull(), eq("popup"), eq("AZ"), isNull());
        verify(campaignEligibilityService).popup(isNull(), eq("banner"), eq("AZ"), isNull());
    }

    @Test
    void getActiveCampaign_languageIsResolvedFromRequestAndPassedToService() throws Exception {
        when(campaignEligibilityService.popup(isNull(), eq("popup"), eq("EN"), isNull()))
                .thenReturn(emptyResponse());
        when(campaignEligibilityService.popup(isNull(), eq("popup"), eq("RU"), isNull()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/campaigns/active")
                        .param("lang", "en")
                        .header("Accept-Language", "az"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/campaigns/active")
                        .header("Accept-Language", "ru"))
                .andExpect(status().isOk());

        verify(campaignEligibilityService).popup(isNull(), eq("popup"), eq("EN"), isNull());
        verify(campaignEligibilityService).popup(isNull(), eq("popup"), eq("RU"), isNull());
    }

    @Test
    void getActiveCampaign_authenticatedUserIsResolvedAndPassedToService() throws Exception {
        authenticateAs(100L);
        when(campaignEligibilityService.popup(eq(100L), eq("popup"), eq("AZ"), isNull()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/campaigns/active").header("Accept-Language", "az"))
                .andExpect(status().isOk());

        verify(campaignEligibilityService).popup(eq(100L), eq("popup"), eq("AZ"), isNull());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/campaigns/{id}
    // ------------------------------------------------------------------

    @Test
    void getCampaignDetail_unknownId_withProductionHandlerWired_returns500() throws Exception {
        when(campaignEligibilityService.getCampaignDetail(999L, "AZ"))
                .thenThrow(new IllegalArgumentException("Campaign not found with id: 999"));

        mockMvc.perform(get("/api/v1/campaigns/999").header("Accept-Language", "az"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.status").value(500))
                .andExpect(jsonPath("$.error.code").value("RUNTIME_EXCEPTION"));
    }

    @Test
    void getCampaignDetail_unknownId_withoutHandler_illegalArgumentExceptionPropagates() {
        when(campaignEligibilityService.getCampaignDetail(999L, "AZ"))
                .thenThrow(new IllegalArgumentException("Campaign not found with id: 999"));

        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mockMvcNoAdvice.perform(
                        get("/api/v1/campaigns/999").header("Accept-Language", "az")));

        assertTrue(hasCause(thrown, IllegalArgumentException.class),
                "expected IllegalArgumentException in cause chain but got: " + thrown);
    }

    @Test
    void getCampaignDetail_knownId_returns200WithDetailPayload() throws Exception {
        when(campaignEligibilityService.getCampaignDetail(1L, "AZ")).thenReturn(detailDto());

        mockMvc.perform(get("/api/v1/campaigns/1").header("Accept-Language", "az"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.code").value("OCT_2026"))
                .andExpect(jsonPath("$.offers[0].totalMonths").value(4))
                .andExpect(jsonPath("$.terms[0].positive").value(true));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/campaigns/{id}/impressions
    // ------------------------------------------------------------------

    @Test
    void recordImpression_explicitPopupContext_isPassedThrough() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns/7/impressions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":\"POPUP\"}"))
                .andExpect(status().isOk());

        verify(campaignEligibilityService).recordImpression(isNull(), eq(7L), eq("POPUP"));
    }

    @Test
    void recordImpression_emptyRequestBody_defaultsToPopup() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns/7/impressions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(campaignEligibilityService).recordImpression(isNull(), eq(7L), eq("POPUP"));
    }

    @Test
    void recordImpression_customContext_isPassedThroughVerbatim() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns/7/impressions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":\"banner\"}"))
                .andExpect(status().isOk());

        // The controller forwards the raw context; the service is responsible for upper-casing.
        verify(campaignEligibilityService).recordImpression(isNull(), eq(7L), eq("banner"));
    }

    @Test
    void recordImpression_authenticatedUser_isForwardedToService() throws Exception {
        authenticateAs(100L);

        mockMvc.perform(post("/api/v1/campaigns/7/impressions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(campaignEligibilityService).recordImpression(eq(100L), eq(7L), eq("POPUP"));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/me/subscriptions/campaign-banner/dismiss
    // ------------------------------------------------------------------

    @Test
    void dismissCampaignBanner_withoutActiveSubscriptions_returns200AndReadsBakuClock() throws Exception {
        when(subscriptionRepository.findByUserIdAndStatusIn(isNull(), eq(List.of("ACTIVE", "FROZEN"))))
                .thenReturn(List.of());
        when(campaignEligibilityService.getBakuNow()).thenReturn(BAKU_NOW);

        mockMvc.perform(post("/api/v1/me/subscriptions/campaign-banner/dismiss"))
                .andExpect(status().isOk());

        verify(campaignEligibilityService).getBakuNow();
        verify(subscriptionRepository, times(1))
                .findByUserIdAndStatusIn(isNull(), eq(List.of("ACTIVE", "FROZEN")));
    }

    @Test
    void dismissCampaignBanner_withActiveSubscription_stampsDismissedAtAndSaves() throws Exception {
        authenticateAs(100L);
        Subscription sub = new Subscription();
        sub.setSubscriptionId(55L);
        sub.setUserId(100L);
        sub.setStatus("ACTIVE");
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(100L), eq(List.of("ACTIVE", "FROZEN"))))
                .thenReturn(List.of(sub));
        when(campaignEligibilityService.getBakuNow()).thenReturn(BAKU_NOW);

        mockMvc.perform(post("/api/v1/me/subscriptions/campaign-banner/dismiss"))
                .andExpect(status().isOk());

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertNotNull(captor.getValue().getCampaignBannerDismissedAt());
        assertTrue(captor.getValue().getCampaignBannerDismissedAt().isEqual(BAKU_NOW),
                "dismissedAt must be the Baku clock reading returned by the service");
    }

    @Test
    void dismissCampaignBanner_withMultipleActiveSubscriptions_savesEachOfThem() throws Exception {
        authenticateAs(100L);
        Subscription first = new Subscription();
        first.setSubscriptionId(55L);
        Subscription second = new Subscription();
        second.setSubscriptionId(56L);
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(100L), eq(List.of("ACTIVE", "FROZEN"))))
                .thenReturn(List.of(first, second));
        when(campaignEligibilityService.getBakuNow()).thenReturn(BAKU_NOW);

        mockMvc.perform(post("/api/v1/me/subscriptions/campaign-banner/dismiss"))
                .andExpect(status().isOk());

        verify(subscriptionRepository, times(2)).save(any(Subscription.class));
        assertNotNull(first.getCampaignBannerDismissedAt());
        assertNotNull(second.getCampaignBannerDismissedAt());
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        Throwable current = thrown;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
