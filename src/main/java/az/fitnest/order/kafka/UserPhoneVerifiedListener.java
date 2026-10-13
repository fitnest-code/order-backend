package az.fitnest.order.kafka;

import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import az.fitnest.order.service.promo.PromoPhoneHasher;
import az.fitnest.order.service.promo.PromoGrantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Consumes user.phone.verified published by identity-backend.
 * Payload: { userId, phoneNormalised, verifiedAt }
 * For each verified user phone, we create grants for any campaign whose
 * audience list contains that phone hash (and whose audience type is
 * UPLOADED_LIST). BR-8 is enforced inside PromoGrantService.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserPhoneVerifiedListener {

    private final PromoAudienceNumberRepository audienceNumberRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoGrantService grantService;
    private final PromoGrantRepository grantRepository;
    private final PromoPhoneHasher phoneHasher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "${promo.kafka.topic.user-phone-verified:user.phone.verified}",
            groupId = "${promo.kafka.group-id:order-backend-promo}")
    @Transactional
    public void onUserPhoneVerified(String payload) {
        Long userId;
        String phoneNormalised;
        try {
            JsonNode node = objectMapper.readTree(payload);
            userId = node.hasNonNull("userId") ? node.get("userId").asLong() : null;
            phoneNormalised = node.hasNonNull("phoneNormalised") ? node.get("phoneNormalised").asText() : null;
            if (userId == null || phoneNormalised == null) {
                return;
            }
        } catch (Exception e) {
            log.warn("Failed parsing user.phone.verified payload: {}", e.getMessage());
            return;
        }

        // Phone is assumed already normalised by identity-backend, but normalise defensively.
        String normalized = phoneHasher.normalizePhone(phoneNormalised);
        if (normalized == null) return;
        String phoneHash = phoneHasher.hashPhone(normalized);

        OffsetDateTime now = OffsetDateTime.now();
        List<PromoCampaign> campaigns = campaignRepository.findByStatusIn(List.of(
                PromoCampaignStatus.DRAFT, PromoCampaignStatus.SCHEDULED,
                PromoCampaignStatus.ACTIVE, PromoCampaignStatus.PAUSED));

        for (PromoCampaign campaign : campaigns) {
            if (campaign.getAudienceType() != PromoAudienceType.UPLOADED_LIST) {
                continue; // NEW_USERS / ALL_USERS grants handled elsewhere
            }
            if (audienceNumberRepository.existsByCampaignIdAndPhoneHash(campaign.getId(), phoneHash)) {
                try {
                    grantService.createGrant(campaign.getId(), userId, phoneHash);
                } catch (Exception e) {
                    log.warn("Failed creating grant for user={} campaign={}: {}", userId, campaign.getId(), e.getMessage());
                }
            }
        }
    }
}
