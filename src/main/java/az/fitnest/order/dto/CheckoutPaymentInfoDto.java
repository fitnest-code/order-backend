package az.fitnest.order.dto;

import lombok.Builder;

@Builder
public record CheckoutPaymentInfoDto(
    String provider,
    String paymentIntentClientSecret,
    String paymentUrl,
    String status
) {}
