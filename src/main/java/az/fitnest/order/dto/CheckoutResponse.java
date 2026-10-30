package az.fitnest.order.dto;

import lombok.Builder;

@Builder
public record CheckoutResponse(
    String orderId,
    String status,
    Double amount,
    String currency,
    CheckoutPaymentInfoDto payment
) {}
