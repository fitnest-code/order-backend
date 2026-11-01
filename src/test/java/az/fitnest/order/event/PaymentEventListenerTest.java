package az.fitnest.order.event;

import az.fitnest.order.service.promo.PromoOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Payment failures must free the promo reservation; everything else on the topic is ignored.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    @Mock
    private PromoOrderService promoOrderService;

    private PaymentEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentEventListener(new ObjectMapper(), promoOrderService);
    }

    @Test
    void paymentFailed_releasesReservation() {
        listener.consumePaymentEvent(
                "{\"eventType\":\"PAYMENT_FAILED\",\"orderId\":\"ord_1\",\"status\":\"ERROR\"}");

        verify(promoOrderService).releasePromoReservation("ord_1");
    }

    @Test
    void otherEventTypes_areIgnored() {
        listener.consumePaymentEvent("{\"eventType\":\"PAYMENT_SUCCEEDED\",\"orderId\":\"ord_1\"}");
        listener.consumePaymentEvent("{\"eventType\":\"PAYMENT_REVERSED\",\"orderId\":\"ord_1\"}");
        listener.consumePaymentEvent("{\"eventType\":\"CARD_REGISTERED\",\"orderId\":\"ord_1\"}");

        verify(promoOrderService, never()).releasePromoReservation(anyString());
    }

    @Test
    void malformedPayload_isDiscarded() {
        assertDoesNotThrow(() -> listener.consumePaymentEvent("not-json"));

        verify(promoOrderService, never()).releasePromoReservation(anyString());
    }

    @Test
    void paymentFailedWithoutOrderId_isIgnored() {
        listener.consumePaymentEvent("{\"eventType\":\"PAYMENT_FAILED\"}");

        verify(promoOrderService, never()).releasePromoReservation(anyString());
    }
}
