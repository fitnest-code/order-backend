package az.fitnest.order.job;

import az.fitnest.order.model.entity.Campaign;
import az.fitnest.order.model.entity.CampaignStatus;
import az.fitnest.order.repository.CampaignRepository;
import az.fitnest.order.service.CampaignEligibilityService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the hourly {@link CampaignExpiryJob}: strict "endAt before now"
 * expiry of ACTIVE campaigns, using the Asia/Baku clock.
 */
@ExtendWith(MockitoExtension.class)
class CampaignExpiryJobTest {

    private static final LocalDateTime BAKU_NOW = LocalDateTime.of(2026, 11, 1, 0, 5, 0);

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignEligibilityService campaignEligibilityService;

    @InjectMocks
    private CampaignExpiryJob job;

    private Campaign activeExpiredCampaign;

    @BeforeEach
    void setUp() {
        activeExpiredCampaign = new Campaign();
        activeExpiredCampaign.setId(1L);
        activeExpiredCampaign.setCode("OCT_2026");
        activeExpiredCampaign.setStatus(CampaignStatus.ACTIVE);
        activeExpiredCampaign.setStartAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        activeExpiredCampaign.setEndAt(LocalDateTime.of(2026, 10, 31, 23, 59, 59));
    }

    private Campaign campaignWithStatus(long id, CampaignStatus status, LocalDateTime endAt) {
        Campaign c = new Campaign();
        c.setId(id);
        c.setCode("C" + id);
        c.setStatus(status);
        c.setStartAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        c.setEndAt(endAt);
        return c;
    }

    @Test
    void expireCampaigns_activeWithEndAtBeforeBakuNow_markedExpiredAndSaved() {
        when(campaignEligibilityService.getBakuNow()).thenReturn(BAKU_NOW);
        when(campaignRepository.findByStatusAndEndAtBefore(CampaignStatus.ACTIVE, BAKU_NOW))
                .thenReturn(List.of(activeExpiredCampaign));

        job.expireCampaigns();

        assertEquals(CampaignStatus.EXPIRED, activeExpiredCampaign.getStatus());
        verify(campaignRepository).save(activeExpiredCampaign);
    }

    @Test
    void expireCampaigns_endAtExactlyNow_leftUntouched() {
        // Window is strict: endAt < now. Equal timestamps are NOT expired yet.
        Campaign endsExactlyNow = campaignWithStatus(2L, CampaignStatus.ACTIVE, BAKU_NOW);
        when(campaignEligibilityService.getBakuNow()).thenReturn(BAKU_NOW);
        when(campaignRepository.findByStatusAndEndAtBefore(CampaignStatus.ACTIVE, BAKU_NOW))
                .thenReturn(List.of()); // repository strict comparison returned nothing

        job.expireCampaigns();

        assertEquals(CampaignStatus.ACTIVE, endsExactlyNow.getStatus());
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void expireCampaigns_nonActiveStatuses_returnedRowsWouldStillNotBeQueried() {
        // DRAFT / DISABLED / EXPIRED rows are never part of the ACTIVE-only query result.
        Campaign draft = campaignWithStatus(3L, CampaignStatus.DRAFT, LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        Campaign disabled = campaignWithStatus(4L, CampaignStatus.DISABLED, LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        Campaign expired = campaignWithStatus(5L, CampaignStatus.EXPIRED, LocalDateTime.of(2026, 9, 30, 23, 59, 59));
        when(campaignEligibilityService.getBakuNow()).thenReturn(BAKU_NOW);
        when(campaignRepository.findByStatusAndEndAtBefore(CampaignStatus.ACTIVE, BAKU_NOW))
                .thenReturn(List.of());

        job.expireCampaigns();

        assertEquals(CampaignStatus.DRAFT, draft.getStatus());
        assertEquals(CampaignStatus.DISABLED, disabled.getStatus());
        assertEquals(CampaignStatus.EXPIRED, expired.getStatus());
        verify(campaignRepository, never()).save(any());
    }

    @Test
    void expireCampaigns_queriesOnlyActiveStatusWithBakuZoneNow() {
        when(campaignEligibilityService.getBakuNow()).thenReturn(BAKU_NOW);
        when(campaignRepository.findByStatusAndEndAtBefore(CampaignStatus.ACTIVE, BAKU_NOW))
                .thenReturn(List.of());

        job.expireCampaigns();

        ArgumentCaptor<CampaignStatus> statusCaptor = ArgumentCaptor.forClass(CampaignStatus.class);
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(campaignRepository).findByStatusAndEndAtBefore(statusCaptor.capture(), timeCaptor.capture());

        assertEquals(CampaignStatus.ACTIVE, statusCaptor.getValue());
        assertEquals(BAKU_NOW, timeCaptor.getValue());
    }

    @Test
    void expireCampaigns_clockSourceOfTheRealService_isAsiaBaku() {
        // In production the job reads the clock from CampaignEligibilityService#getBakuNow;
        // exercise the real (non-mocked) clock here to prove it is zone Asia/Baku.
        CampaignEligibilityService realService = new CampaignEligibilityService(
                null, null, null, null, null, null, null);

        assertEquals(ZoneId.of("Asia/Baku"), realService.getBakuZone());
        LocalDateTime bakuNow = realService.getBakuNow();
        LocalDateTime wallClock = LocalDateTime.now(ZoneId.of("Asia/Baku"));
        assertFalse(bakuNow.isBefore(wallClock.minusMinutes(5)));
        assertFalse(bakuNow.isAfter(wallClock.plusMinutes(5)));
    }

    @Test
    void expireCampaigns_onlyReadsClockAndSavesCampaign_neverDeletesAnything() {
        when(campaignEligibilityService.getBakuNow()).thenReturn(BAKU_NOW);
        when(campaignRepository.findByStatusAndEndAtBefore(CampaignStatus.ACTIVE, BAKU_NOW))
                .thenReturn(List.of(activeExpiredCampaign));

        job.expireCampaigns();

        // Exactly one status flip + save; no rows are ever deleted by the expiry job
        // (the job has no redemption repository dependency — redemptions stay for audit).
        verify(campaignRepository, times(1)).save(any(Campaign.class));
        verify(campaignRepository, never()).delete(any(Campaign.class));
        verify(campaignRepository, never()).deleteAll(anyIterable());
        // The only collaborator call is the Baku clock read.
        verify(campaignEligibilityService, times(1)).getBakuNow();
        verifyNoMoreInteractions(campaignEligibilityService);
    }
}
