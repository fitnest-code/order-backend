package az.fitnest.order.pricingcampaign.dto;

import java.math.BigDecimal;

/** One duration tier of a campaign: {@code months} -> {@code percent} off the list price. */
public record PricingDiscountDto(Integer months, BigDecimal percent) {}
