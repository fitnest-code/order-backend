package az.fitnest.order.event;

import az.fitnest.order.model.entity.Order;
import az.fitnest.order.pricingcampaign.service.PricingCampaignService;
import az.fitnest.order.repository.OrderRepository;
import az.fitnest.order.service.promo.PromoOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Payment failures free promo reservations; successes redeem pricing campaigns when present.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    @Mock private PromoOrderService promoOrderService;
    @Mock private OrderRepository orderRepository;
    @Mock private PricingCampaignService pricingCampaignService;

    private PaymentEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentEventListener(
                new ObjectMapper(), promoOrderService, orderRepository, pricingCampaignService);
    }

    @Test
    void paymentFailed_releasesReservation() {
        listener.consumePaymentEvent(
                "{\"eventType\":\"PAYMENT_FAILED\",\"orderId\":\"ord_1\",\"status\":\"ERROR\"}");

        verify(promoOrderService).releasePromoReservation("ord_1");
        verify(pricingCampaignService, never()).markRedeemed(anyLong(), any(), anyString());
    }

    @Test
    void paymentSucceeded_redeemsFromPayload() {
        UUID campaignId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        listener.consumePaymentEvent(
                "{\"eventType\":\"PAYMENT_SUCCEEDED\",\"orderId\":\"ord_1\",\"userId\":9,"
                        + "\"campaignId\":\"" + campaignId + "\"}");

        verify(pricingCampaignService).markRedeemed(eq(9L), eq(campaignId), eq("ord_1"));
        verify(promoOrderService, never()).releasePromoReservation(anyString());
    }

    @Test
    void paymentSucceeded_fallsBackToOrderSnapshot() {
        UUID campaignId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        Order order = Order.builder().orderId("ord_2").userId(5L).pricingCampaignId(campaignId).build();
        when(orderRepository.findById("ord_2")).thenReturn(Optional.of(order));

        listener.consumePaymentEvent("{\"eventType\":\"PAYMENT_SUCCEEDED\",\"orderId\":\"ord_2\"}");

        verify(pricingCampaignService).markRedeemed(eq(5L), eq(campaignId), eq("ord_2"));
    }

    @Test
    void otherEventTypes_areIgnored() {
        listener.consumePaymentEvent("{\"eventType\":\"PAYMENT_REVERSED\",\"orderId\":\"ord_1\"}");
        listener.consumePaymentEvent("{\"eventType\":\"CARD_REGISTERED\",\"orderId\":\"ord_1\"}");

        verify(promoOrderService, never()).releasePromoReservation(anyString());
        verify(pricingCampaignService, never()).markRedeemed(anyLong(), any(), anyString());
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
