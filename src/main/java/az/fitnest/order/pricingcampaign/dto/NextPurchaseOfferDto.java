package az.fitnest.order.pricingcampaign.dto;

import java.util.List;
import java.util.UUID;

/** Offer shown to users who already hold a subscription and may queue the next one at campaign prices. */
public record NextPurchaseOfferDto(boolean available, UUID campaignId, List<PricingDiscountDto> discounts) {}
