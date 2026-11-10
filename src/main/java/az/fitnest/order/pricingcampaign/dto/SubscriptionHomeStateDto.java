package az.fitnest.order.pricingcampaign.dto;

public record SubscriptionHomeStateDto(
        boolean hasActiveSubscription,
        boolean showCountdown,
        ActivePricingCampaignDto campaign,
        StartingFromDto startingFrom,
        NextPurchaseOfferDto nextPurchaseOffer) {}
