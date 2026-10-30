package az.fitnest.order.service;

import az.fitnest.order.client.PlanCampaignDirectoryClient;
import az.fitnest.order.model.entity.PlanCampaign;
import az.fitnest.order.repository.PlanCampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanCampaignServiceTest {
    private final PlanCampaignRepository repository = mock(PlanCampaignRepository.class);
    private final PlanCampaignDirectoryClient directory = mock(PlanCampaignDirectoryClient.class);
    private final PlanCampaignService service = new PlanCampaignService(repository, directory);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-26T15:00:00+04:00");
    private PlanCampaign campaign;

    @BeforeEach void setup() {
        campaign = new PlanCampaign(); campaign.setId(UUID.randomUUID());
        campaign.setCode("BAKCELL_PLANS"); campaign.setEnabled(true);
        campaign.setStartsAt(now.minusDays(1)); campaign.setEndsAt(now.plusDays(1));
        campaign.setDurationMonths(new Integer[]{3, 6, 12}); campaign.setPackageIds(new Long[]{1L, 4L});
        campaign.setContent(Map.of("az", new PlanCampaign.Content("Başlıq", "Mətn", "Təkliflər", "https://media.fitnest.az/banner.png", null, null, null, "Banner")));
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of(campaign));
        when(directory.phoneFor(10L)).thenReturn(Optional.of("+994551234567"));
    }
    @ParameterizedTest @ValueSource(strings={"0551234567", "0991234567", "+994551234567", "994991234567", "00994551234567", "+994 (99) 123-45-67", "551234567"})
    void acceptsOnlyCanonicalBakcellPrefixes(String phone) { assertTrue(PlanCampaignService.eligiblePhone(phone)); }
    @ParameterizedTest @ValueSource(strings={"0501234567", "+994701234567", "+994771234567", "+995551234567", "055123456", "05512345678", "not-a-phone", "+994+551234567", "+994551234567&phone=0991234567"})
    void rejectsOtherOrMalformedPhones(String phone) { assertFalse(PlanCampaignService.eligiblePhone(phone)); }
    @Test void unknownPhoneFailsClosed() {
        when(directory.phoneFor(10L)).thenReturn(Optional.empty());
        assertFalse(service.forUser(10L, 4L, "az", now).eligible());
    }
    @Test void unsupportedPrefixHasNoContentOrImages() {
        when(directory.phoneFor(10L)).thenReturn(Optional.of("+994501234567"));
        assertEquals(List.of(), service.forUser(10L, 4L, "az", now).items());
    }
    @Test void eligiblePhoneGetsContentButNoPricingEffect() {
        var result = service.forUser(10L, 4L, "az", now);
        assertTrue(result.eligible()); assertEquals("NONE", result.items().getFirst().pricingEffect());
        assertEquals(List.of(3, 6, 12), result.items().getFirst().durationMonths());
    }
    @Test void unsupportedLocaleFallsBackAsCompleteContentBlock() {
        assertEquals("az", service.forUser(10L, 4L, "en-US,en;q=0.9", now).items().getFirst().locale());
    }
    @Test void restrictedPackageDoesNotLeakBanner() { assertFalse(service.forUser(10L, 2L, "az", now).eligible()); }
    @Test void endBoundaryIsExclusive() { assertFalse(service.forUser(10L, 4L, "az", campaign.getEndsAt()).eligible()); }
    @Test void futureCampaignIsHidden() { assertFalse(service.forUser(10L, 4L, "az", campaign.getStartsAt().minusSeconds(1)).eligible()); }
    @Test void missingBannerIsHidden() {
        campaign.setContent(Map.of("az", new PlanCampaign.Content("Title", "Body", "Offers", null, null, null, null, "Alt")));
        assertFalse(service.forUser(10L, 4L, "az", now).eligible());
    }
    @Test void noEnabledCampaignSkipsDirectory() {
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());
        assertFalse(service.forUser(10L, 4L, "az", now).eligible()); verifyNoInteractions(directory);
    }
    @Test void anonymousAccountCannotGetContent() {
        when(directory.phoneFor(null)).thenReturn(Optional.empty());
        assertFalse(service.forUser(null, 4L, "az", now).eligible());
    }
}
