package az.fitnest.order.pricingcampaign.dto;

import java.math.BigDecimal;

public record CheckoutPreviewResponseV2(
        BigDecimal packagePrice,
        PricingDto campaign,
        FitCoinPart fitCoin,
        BigDecimal total,
        boolean campaignApplied,
        String currency) {

    /** FitCoin is applied AFTER the campaign discount; zeros when not requested or no balance. */
    public record FitCoinPart(boolean used, BigDecimal balanceCoins, BigDecimal balanceAmount,
                              BigDecimal usedCoins, BigDecimal usedAmount) {}
}
