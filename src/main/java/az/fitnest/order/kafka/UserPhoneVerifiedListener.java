package az.fitnest.order.kafka;

import az.fitnest.order.model.entity.promo.PromoAudienceType;
import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoCampaignStatus;
import az.fitnest.order.repository.promo.PromoAudienceNumberRepository;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import az.fitnest.order.service.promo.PromoGrantService;
import az.fitnest.order.service.promo.PromoPhoneHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Consumes user.phone.verified published by identity-backend.
 * Payload: { userId, phoneNormalised, verifiedAt }
 *
 * <p>Creates a grant in:
 * <ul>
 *   <li>every {@code UPLOADED_LIST} campaign containing that phone hash, and</li>
 *   <li>every active/scheduled {@code ALL_USERS} or {@code NEW_USERS} campaign
 *       whose allowed prefixes match the verified number.</li>
 * </ul>
 * BR-8 (one account per number) is enforced inside PromoGrantService.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserPhoneVerifiedListener {

    private final PromoAudienceNumberRepository audienceNumberRepository;
    private final PromoCampaignRepository campaignRepository;
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

        // Uploaded-list campaigns are matched by the hashed number (one indexed lookup).
        Set<UUID> campaignIds = new LinkedHashSet<>(
                audienceNumberRepository.findCampaignIdsByPhoneHash(phoneHash));

        // Prefix-based campaigns are matched without storing PII.
        List<PromoCampaign> automatedCampaigns = campaignRepository.findByAudienceTypeInAndStatusIn(
                List.of(PromoAudienceType.ALL_USERS, PromoAudienceType.NEW_USERS),
                List.of(PromoCampaignStatus.ACTIVE, PromoCampaignStatus.SCHEDULED));
        for (PromoCampaign campaign : automatedCampaigns) {
            if (phoneHasher.matchesAllowedPrefix(normalized, campaign.getAllowedPrefixes())) {
                campaignIds.add(campaign.getId());
            }
        }

        for (UUID campaignId : campaignIds) {
            try {
                grantService.createGrant(campaignId, userId, phoneHash);
            } catch (Exception e) {
                log.warn("Failed creating grant for user={} campaign={}: {}", userId, campaignId, e.getMessage());
            }
        }
    }
}
