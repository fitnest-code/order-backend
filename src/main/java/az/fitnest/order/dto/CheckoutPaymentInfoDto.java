package az.fitnest.order.dto;

import lombok.Builder;

@Builder
public record CheckoutPaymentInfoDto(
    String provider,
    String payment_intent_client_secret,
    String payment_url,
    String status
) {}
