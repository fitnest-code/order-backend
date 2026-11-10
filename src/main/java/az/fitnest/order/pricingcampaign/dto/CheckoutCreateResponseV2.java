package az.fitnest.order.pricingcampaign.dto;

import az.fitnest.order.dto.CheckoutPaymentInfoDto;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutCreateResponseV2(
        String orderId,
        String status,
        BigDecimal amount,
        String currency,
        CheckoutPaymentInfoDto payment,
        boolean campaignApplied,
        UUID campaignId) {}
