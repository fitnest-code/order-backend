package az.fitnest.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.fitnest.order.dto.CampaignDetailDto;
import az.fitnest.order.dto.CampaignPushRequest;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.CampaignNotAvailableException;
import az.fitnest.order.grpc.NotificationGrpcClient;
import az.fitnest.order.model.entity.Campaign;
import az.fitnest.order.model.entity.CampaignStatus;
import az.fitnest.order.repository.CampaignRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CampaignV2ServiceTest {

    private final CampaignRepository campaigns = org.mockito.Mockito.mock(CampaignRepository.class);
    private final CampaignEligibilityService eligibility = org.mockito.Mockito.mock(CampaignEligibilityService.class);
    private final NotificationGrpcClient notifications = org.mockito.Mockito.mock(NotificationGrpcClient.class);
    private final CampaignV2Service service = new CampaignV2Service(campaigns, eligibility, notifications);

    @Test
    void liveWindowIncludesTheStartAndEndInstants() {
        Campaign campaign = activeCampaign();
        LocalDateTime start = campaign.getStartAt();
        LocalDateTime end = campaign.getEndAt();

        assertTrue(CampaignV2Service.isCurrentlyLive(campaign, start));
        assertTrue(CampaignV2Service.isCurrentlyLive(campaign, end));
        assertFalse(CampaignV2Service.isCurrentlyLive(campaign, start.minusSeconds(1)));
        assertFalse(CampaignV2Service.isCurrentlyLive(campaign, end.plusSeconds(1)));

        campaign.setStatus(CampaignStatus.EXPIRED);
        assertFalse(CampaignV2Service.isCurrentlyLive(campaign, start.plusDays(1)));
    }

    @Test
    void endedCampaignDetailIsGone() {
        Campaign campaign = activeCampaign();
        campaign.setStatus(CampaignStatus.DISABLED);
        when(campaigns.findById(87L)).thenReturn(Optional.of(campaign));
        when(eligibility.getBakuNow()).thenReturn(LocalDateTime.of(2026, 6, 1, 12, 0));

        assertThrows(CampaignNotAvailableException.class, () -> service.getAvailableDetail(87L, "az"));
    }

    @Test
    void pushRequiresEveryLanguage() {
        when(campaigns.findById(87L)).thenReturn(Optional.of(activeCampaign()));
        when(eligibility.getBakuNow()).thenReturn(LocalDateTime.of(2026, 6, 1, 12, 0));

        CampaignPushRequest request = new CampaignPushRequest();
        request.setContents(Map.of("az", text("A", "B"), "en", text("A", "B")));

        assertThrows(BadRequestException.class, () -> service.push(87L, request));
    }

    @Test
    void pushSendsTheCampaignRoute() {
        when(campaigns.findById(87L)).thenReturn(Optional.of(activeCampaign()));
        when(eligibility.getBakuNow()).thenReturn(LocalDateTime.of(2026, 6, 1, 12, 0));
        when(notifications.broadcastLocalizedPush(any(), any())).thenReturn(4);

        CampaignPushRequest request = new CampaignPushRequest();
        request.setContents(Map.of(
                "az", text("AZ", "az body"),
                "en", text("EN", "en body"),
                "ru", text("RU", "ru body")));

        assertEquals(4, service.push(87L, request));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(notifications).broadcastLocalizedPush(any(), data.capture());
        assertEquals("CAMPAIGN", data.getValue().get("type"));
        assertEquals("CAMPAIGN", data.getValue().get("targetType"));
        assertEquals("87", data.getValue().get("targetId"));
        assertEquals("fitnest://campaigns/87", data.getValue().get("deeplink"));
    }

    private static Campaign activeCampaign() {
        Campaign campaign = new Campaign();
        campaign.setId(87L);
        campaign.setStatus(CampaignStatus.ACTIVE);
        campaign.setStartAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        campaign.setEndAt(LocalDateTime.of(2026, 12, 31, 23, 59));
        return campaign;
    }

    private static CampaignPushRequest.LocalizedText text(String title, String body) {
        CampaignPushRequest.LocalizedText text = new CampaignPushRequest.LocalizedText();
        text.setTitle(title);
        text.setBody(body);
        return text;
    }
}
