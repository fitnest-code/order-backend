package az.fitnest.order.service.promo;

import az.fitnest.order.client.PlanCampaignDirectoryClient;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.promo.PromoAudienceType;
import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoGrant;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromoGrantBackfillServiceTest {

    @Mock
    private PromoCampaignRepository campaignRepository;
    @Mock
    private PlanCampaignDirectoryClient directoryClient;
    @Mock
    private PromoGrantService grantService;
    @Mock
    private PromoPhoneHasher phoneHasher;

    @InjectMocks
    private PromoGrantBackfillService backfillService;

    private UUID campaignId;
    private PromoCampaign campaign;

    @BeforeEach
    void setUp() {
        campaignId = UUID.randomUUID();
        campaign = new PromoCampaign();
        campaign.setId(campaignId);
        campaign.setAudienceType(PromoAudienceType.ALL_USERS);
        campaign.setAllowedPrefixes(new String[]{"55", "99"});
        campaign.setIncludeExistingUsers(true);

        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(phoneHasher.normalizePhone(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(phoneHasher.hashPhone(anyString()))
                .thenAnswer(invocation -> "hash-" + invocation.getArgument(0));
        when(phoneHasher.matchesAllowedPrefix(anyString(), any())).thenCallRealMethod();
        when(grantService.createGrant(any(), anyLong(), anyString())).thenReturn(new PromoGrant());
    }

    @Test
    void backfill_pagesThroughPrefixAndCreatesGrantsForMatchingNumbers() {
        when(directoryClient.listUsersByPrefix("55", 0, PromoGrantBackfillService.PAGE_SIZE))
                .thenReturn(page(
                        user(10L, "+994551111111"),
                        user(11L, "+994504444444"), true));
        when(directoryClient.listUsersByPrefix("55", 1, PromoGrantBackfillService.PAGE_SIZE))
                .thenReturn(page(user(12L, "+994557777777"), false));
        when(directoryClient.listUsersByPrefix("99", 0, PromoGrantBackfillService.PAGE_SIZE))
                .thenReturn(page(user(13L, "+994991234567"), false));

        PromoGrantBackfillService.BackfillResult result = backfillService.backfill(campaignId);

        assertEquals("completed", result.status());
        assertEquals(4, result.scanned());
        assertEquals(3, result.created());
        assertEquals(1, result.skipped());
        verify(grantService).createGrant(campaignId, 10L, "hash-+994551111111");
        verify(grantService).createGrant(campaignId, 12L, "hash-+994557777777");
        verify(grantService).createGrant(campaignId, 13L, "hash-+994991234567");
        verify(grantService, never()).createGrant(campaignId, 11L, "hash-+994504444444");
    }

    @Test
    void backfill_countsExistingGrantAsSkipped() {
        campaign.setAllowedPrefixes(new String[]{"55"});
        when(directoryClient.listUsersByPrefix("55", 0, PromoGrantBackfillService.PAGE_SIZE))
                .thenReturn(page(user(10L, "+994551111111"), false));
        when(grantService.createGrant(any(), anyLong(), anyString())).thenReturn(null);

        PromoGrantBackfillService.BackfillResult result = backfillService.backfill(campaignId);

        assertEquals(1, result.scanned());
        assertEquals(0, result.created());
        assertEquals(1, result.skipped());
    }

    @Test
    void backfill_skipsUploadedListCampaigns() {
        campaign.setAudienceType(PromoAudienceType.UPLOADED_LIST);

        PromoGrantBackfillService.BackfillResult result = backfillService.backfill(campaignId);

        assertEquals("skipped", result.status());
        verifyNoInteractions(directoryClient);
        verify(grantService, never()).createGrant(any(), anyLong(), anyString());
    }

    @Test
    void backfill_skipsWhenExistingUsersAreExcluded() {
        campaign.setIncludeExistingUsers(false);

        PromoGrantBackfillService.BackfillResult result = backfillService.backfill(campaignId);

        assertEquals("skipped", result.status());
        verifyNoInteractions(directoryClient);
    }

    @Test
    void backfill_unknownCampaignThrows() {
        UUID unknown = UUID.randomUUID();
        when(campaignRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> backfillService.backfill(unknown));
    }

    private static PlanCampaignDirectoryClient.DirectoryUser user(Long id, String mobile) {
        return new PlanCampaignDirectoryClient.DirectoryUser(id, mobile);
    }

    private static PlanCampaignDirectoryClient.DirectoryUserPage page(
            PlanCampaignDirectoryClient.DirectoryUser user, boolean hasNext) {
        return new PlanCampaignDirectoryClient.DirectoryUserPage(
                List.of(user), 0, PromoGrantBackfillService.PAGE_SIZE, 1, 1, hasNext);
    }

    private static PlanCampaignDirectoryClient.DirectoryUserPage page(
            PlanCampaignDirectoryClient.DirectoryUser first,
            PlanCampaignDirectoryClient.DirectoryUser second,
            boolean hasNext) {
        return new PlanCampaignDirectoryClient.DirectoryUserPage(
                List.of(first, second), 0, PromoGrantBackfillService.PAGE_SIZE, 2, 1, hasNext);
    }
}
