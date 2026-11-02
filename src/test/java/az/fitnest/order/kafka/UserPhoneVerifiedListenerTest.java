package az.fitnest.order.kafka;

import az.fitnest.order.model.entity.promo.PromoAudienceType;
import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoCampaignStatus;
import az.fitnest.order.repository.promo.PromoAudienceNumberRepository;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import az.fitnest.order.service.promo.PromoGrantService;
import az.fitnest.order.service.promo.PromoPhoneHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPhoneVerifiedListenerTest {

    @Mock
    private PromoAudienceNumberRepository audienceNumberRepository;
    @Mock
    private PromoCampaignRepository campaignRepository;
    @Mock
    private PromoGrantService grantService;

    private PromoPhoneHasher phoneHasher;
    private UserPhoneVerifiedListener listener;

    private UUID prefixCampaignId;

    @BeforeEach
    void setUp() {
        phoneHasher = new PromoPhoneHasher();
        ReflectionTestUtils.setField(phoneHasher, "hmacKey", "test-key");
        listener = new UserPhoneVerifiedListener(
                audienceNumberRepository, campaignRepository, grantService, phoneHasher, new ObjectMapper());

        prefixCampaignId = UUID.randomUUID();
        PromoCampaign campaign = new PromoCampaign();
        campaign.setId(prefixCampaignId);
        campaign.setAudienceType(PromoAudienceType.ALL_USERS);
        campaign.setStatus(PromoCampaignStatus.ACTIVE);
        campaign.setAllowedPrefixes(new String[]{"55", "99"});

        when(audienceNumberRepository.findCampaignIdsByPhoneHash(anyString())).thenReturn(List.of());
        when(campaignRepository.findByAudienceTypeInAndStatusIn(any(), any())).thenReturn(List.of(campaign));
    }

    @Test
    void new055UserGetsPrefixCampaignGrant() {
        listener.onUserPhoneVerified(payload(69L, "+994556666666"));

        verify(grantService).createGrant(eq(prefixCampaignId), eq(69L),
                eq(phoneHasher.hashPhone("+994556666666")));
    }

    @Test
    void new099UserGetsPrefixCampaignGrant() {
        listener.onUserPhoneVerified(payload(70L, "+994991234567"));

        verify(grantService).createGrant(eq(prefixCampaignId), eq(70L),
                eq(phoneHasher.hashPhone("+994991234567")));
    }

    @Test
    void nonBakcellPrefixDoesNotGetPrefixCampaignGrant() {
        listener.onUserPhoneVerified(payload(67L, "+994508000135"));

        verify(grantService, never()).createGrant(any(), anyLong(), anyString());
    }

    @Test
    void uploadedListGrantStillWorks() {
        UUID uploadedCampaignId = UUID.randomUUID();
        when(audienceNumberRepository.findCampaignIdsByPhoneHash(anyString()))
                .thenReturn(List.of(uploadedCampaignId));
        when(campaignRepository.findByAudienceTypeInAndStatusIn(any(), any())).thenReturn(List.of());

        listener.onUserPhoneVerified(payload(2L, "+994550000000"));

        verify(grantService).createGrant(eq(uploadedCampaignId), eq(2L),
                eq(phoneHasher.hashPhone("+994550000000")));
    }

    private static String payload(Long userId, String phone) {
        return "{\"userId\":" + userId + ",\"phoneNormalised\":\"" + phone + "\"}";
    }
}
