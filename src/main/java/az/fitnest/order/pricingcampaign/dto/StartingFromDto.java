package az.fitnest.order.pricingcampaign.dto;

import java.math.BigDecimal;

/** Cheapest per-month price across active packages ("from X AZN / month"). */
public record StartingFromDto(BigDecimal monthlyPrice, BigDecimal monthlyListPrice, Integer months, String currency) {}
