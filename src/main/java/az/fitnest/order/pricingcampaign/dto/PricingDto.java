package az.fitnest.order.pricingcampaign.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Price breakdown for one package option; list price is always priceStandard. */
public record PricingDto(
        BigDecimal listPrice,
        BigDecimal finalPrice,
        BigDecimal savedAmount,
        BigDecimal discountPercent,
        BigDecimal monthlyPrice,
        BigDecimal monthlyListPrice,
        boolean campaignApplied,
        UUID campaignId) {}
