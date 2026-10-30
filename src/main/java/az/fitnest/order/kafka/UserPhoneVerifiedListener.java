package az.fitnest.order.kafka;

import az.fitnest.order.repository.promo.PromoAudienceNumberRepository;
import az.fitnest.order.service.promo.PromoGrantService;
import az.fitnest.order.service.promo.PromoPhoneHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Consumes user.phone.verified published by identity-backend.
 * Payload: { userId, phoneNormalised, verifiedAt }
 * Creates a grant in every list campaign containing that phone hash.
 * BR-8 (one account per number) is enforced inside PromoGrantService.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserPhoneVerifiedListener {

    private final PromoAudienceNumberRepository audienceNumberRepository;
    private final PromoGrantService grantService;
    private final PromoPhoneHasher phoneHasher;
    private final ObjectMapper objectMapper;

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

        // One indexed lookup finds every relevant campaign (no per-campaign EXISTS).
        List<UUID> campaignIds = audienceNumberRepository.findCampaignIdsByPhoneHash(phoneHash);
        for (UUID campaignId : campaignIds) {
            try {
                grantService.createGrant(campaignId, userId, phoneHash);
            } catch (Exception e) {
                log.warn("Failed creating grant for user={} campaign={}: {}", userId, campaignId, e.getMessage());
            }
        }
    }
}
