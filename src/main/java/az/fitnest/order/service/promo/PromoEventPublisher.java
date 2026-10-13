package az.fitnest.order.service.promo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes promo.* events (2.2).
 * All events carry campaignId, code, partnerId, userId (or phoneHash where PII
 * must not leak), occurredAt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromoEventPublisher {

    private static final String TOPIC_PREFIX = "promo.";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void grantCreated(UUID campaignId, String code, UUID partnerId, Long userId, String phoneHash) {
        publish("promo.grant.created", campaignId, code, partnerId, userId, phoneHash, Map.of());
    }

    public void popupSeen(UUID campaignId, String code, UUID partnerId, Long userId, String phoneHash) {
        publish("promo.popup.seen", campaignId, code, partnerId, userId, phoneHash, Map.of());
    }

    public void codeApplied(UUID campaignId, String code, UUID partnerId, Long userId, String phoneHash) {
        publish("promo.code.applied", campaignId, code, partnerId, userId, phoneHash, Map.of());
    }

    public void codeRemoved(UUID campaignId, String code, UUID partnerId, Long userId, String phoneHash) {
        publish("promo.code.removed", campaignId, code, partnerId, userId, phoneHash, Map.of());
    }

    public void grantReserved(UUID campaignId, String code, UUID partnerId, Long userId, String phoneHash, String orderId) {
        publish("promo.grant.reserved", campaignId, code, partnerId, userId, phoneHash, Map.of("orderId", orderId));
    }

    public void grantRedeemed(UUID campaignId, String code, UUID partnerId, Long userId, String phoneHash,
                              String orderId, java.math.BigDecimal discountAmount) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("orderId", orderId);
        extra.put("discountAmount", discountAmount);
        publish("promo.grant.redeemed", campaignId, code, partnerId, userId, phoneHash, extra);
    }

    public void grantReleased(UUID campaignId, String code, UUID partnerId, Long userId, String phoneHash) {
        publish("promo.grant.released", campaignId, code, partnerId, userId, phoneHash, Map.of());
    }

    private void publish(String eventType, UUID campaignId, String code, UUID partnerId,
                         Long userId, String phoneHash, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("eventType", eventType);
        payload.put("campaignId", campaignId);
        payload.put("code", code);
        payload.put("partnerId", partnerId);
        payload.put("userId", userId);
        payload.put("phoneHash", phoneHash);
        payload.put("occurredAt", OffsetDateTime.now().toString());

        try {
            String key = campaignId != null ? campaignId.toString() : code;
            kafkaTemplate.send(TOPIC_PREFIX + eventType.substring("promo.".length()), key, payload);
        } catch (Exception e) {
            // Event publishing must never break the business transaction
            log.warn("Failed publishing event {}: {}", eventType, e.getMessage());
        }
    }
}
