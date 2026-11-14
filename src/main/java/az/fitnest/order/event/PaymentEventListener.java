package az.fitnest.order.event;

import az.fitnest.order.model.entity.Order;
import az.fitnest.order.pricingcampaign.service.PricingCampaignService;
import az.fitnest.order.repository.OrderRepository;
import az.fitnest.order.service.promo.PromoOrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Payment lifecycle hooks for order-backend side effects.
 *
 * <ul>
 *   <li>{@code PAYMENT_FAILED} — release reserved promo grants (existing behavior).</li>
 *   <li>{@code PAYMENT_SUCCEEDED} — mark Black Friday pricing-campaign redemption
 *       (one-shot; refunds do not clear it). Campaign id comes from the order snapshot
 *       or the outbox payload {@code campaignId} when present.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventListener {

    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";

    static final String TOPIC_PAYMENT_EVENTS = "payment-events";
    static final String GROUP_ID = "order-payment-events-group";

    private final ObjectMapper objectMapper;
    private final PromoOrderService promoOrderService;
    private final OrderRepository orderRepository;
    private final PricingCampaignService pricingCampaignService;

    @KafkaListener(topics = TOPIC_PAYMENT_EVENTS, groupId = GROUP_ID)
    public void consumePaymentEvent(String message) {
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(message, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // Malformed payloads cannot succeed on retry; keep the consumer healthy.
            log.error("Discarding malformed payment event: {}", message, e);
            return;
        }

        String eventType = stringValue(event.get("eventType"));
        String orderId = stringValue(event.get("orderId"));
        if (orderId == null || orderId.isBlank()) {
            log.warn("Ignoring payment event without orderId: {}", message);
            return;
        }

        if (PAYMENT_FAILED.equals(eventType)) {
            // Database failures propagate so the container retries instead of losing the release.
            promoOrderService.releasePromoReservation(orderId);
            return;
        }

        if (PAYMENT_SUCCEEDED.equals(eventType)) {
            redeemPricingCampaignIfPresent(event, orderId);
        }
    }

    private void redeemPricingCampaignIfPresent(Map<String, Object> event, String orderId) {
        UUID campaignId = parseUuid(stringValue(event.get("campaignId")));
        Long userId = parseLong(event.get("userId"));
        if (campaignId == null || userId == null) {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                if (campaignId == null) {
                    campaignId = order.getPricingCampaignId();
                }
                if (userId == null) {
                    userId = order.getUserId();
                }
            }
        }
        if (campaignId == null || userId == null) {
            return;
        }
        pricingCampaignService.markRedeemed(userId, campaignId, orderId);
        log.info("Pricing campaign {} redeemed for user {} order {}", campaignId, userId, orderId);
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static Long parseLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = stringValue(value);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
