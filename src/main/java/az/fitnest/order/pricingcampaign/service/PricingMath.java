package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignDiscount;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Pure pricing and eligibility rules for Black Friday, kept free of Spring so they can be unit-tested
 * exhaustively. All money is HALF_UP, 2dp (spec); never derive prices from priceDiscounted.
 */
public final class PricingMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PricingMath() {}

    public static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal finalPrice(BigDecimal listPrice, BigDecimal percent) {
        BigDecimal factor = BigDecimal.ONE.subtract(percent.divide(HUNDRED));
        return round2(listPrice.multiply(factor));
    }

    public static BigDecimal savedAmount(BigDecimal listPrice, BigDecimal finalPrice) {
        return round2(listPrice.subtract(finalPrice));
    }

    public static BigDecimal perMonth(BigDecimal total, int months) {
        return total.divide(BigDecimal.valueOf(Math.max(months, 1)), 2, RoundingMode.HALF_UP);
    }

    /** Tier percent for a duration; month 1 (and any unlisted duration) is 0%. */
    public static BigDecimal percentFor(List<PricingCampaignDiscount> discounts, int months) {
        if (months <= 1 || discounts == null) return BigDecimal.ZERO;
        return discounts.stream()
                .filter(d -> d.getMonths() != null && d.getMonths() == months)
                .map(PricingCampaignDiscount::getPercent)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Window is compared on absolute instants, so the Asia/Baku wall-clock requirement is satisfied by
     * storing starts_at/ends_at with the +04 offset (see V108 seed); no server-zone conversion is needed.
     */
    public static boolean isActiveAt(PricingCampaign campaign, OffsetDateTime now) {
        return campaign != null
                && campaign.getStatus() == PricingCampaignStatus.ACTIVE
                && PricingCampaign.TYPE_BLACK_FRIDAY.equals(campaign.getType())
                && !now.isBefore(campaign.getStartsAt())
                && !now.isAfter(campaign.getEndsAt());
    }

    /** BAKCELL20 holders keep their partner deal and are excluded from Black Friday. */
    public static boolean isEligible(boolean hasBakcell20Grant) {
        return !hasBakcell20Grant;
    }

    /** Countdown is an acquisition hook: hidden for anyone already subscribed (active or frozen). */
    public static boolean showCountdown(boolean campaignActive, boolean hasActiveOrFrozenSub) {
        return campaignActive && !hasActiveOrFrozenSub;
    }

    public static boolean nextPurchaseAvailable(boolean campaignActive, boolean hasActiveSub,
                                                boolean hasPendingQueuedSub, boolean usedByUser) {
        return campaignActive && hasActiveSub && !hasPendingQueuedSub && !usedByUser;
    }

    /** FitCoin comes off the already-discounted price and can never push the total below zero. */
    public static BigDecimal fitCoinUsedAmount(BigDecimal afterCampaign, BigDecimal balanceAzn) {
        if (balanceAzn == null || balanceAzn.signum() <= 0 || afterCampaign.signum() <= 0) return BigDecimal.ZERO;
        return round2(balanceAzn.min(afterCampaign));
    }
}
