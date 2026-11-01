package az.fitnest.order.event;

import az.fitnest.order.service.promo.PromoOrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Releases the promo grant reserved for an order once its payment fails.
 *
 * <p>Without this consumer a declined card left the grant RESERVED for the full
 * reservation window: the quote then answered "another payment is in progress"
 * and the user could not retry with their own unused code. payment-backend
 * publishes {@code PAYMENT_FAILED} for every terminal failure (callback, status
 * sync, wallet failure), so the release happens within seconds of the decline.
 *
 * <p>Releases are idempotent: {@link PromoOrderService#releasePromoReservation}
 * only touches rows still RESERVED for that order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventListener {

    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    static final String TOPIC_PAYMENT_EVENTS = "payment-events";
    static final String GROUP_ID = "order-payment-events-group";

    private final ObjectMapper objectMapper;
    private final PromoOrderService promoOrderService;

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

        if (!PAYMENT_FAILED.equals(stringValue(event.get("eventType")))) {
            return;
        }
        String orderId = stringValue(event.get("orderId"));
        if (orderId == null || orderId.isBlank()) {
            log.warn("Ignoring PAYMENT_FAILED event without orderId: {}", message);
            return;
        }
        // Database failures propagate so the container retries instead of losing the release.
        promoOrderService.releasePromoReservation(orderId);
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString().trim();
    }
}
