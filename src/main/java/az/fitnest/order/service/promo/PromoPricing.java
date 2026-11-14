package az.fitnest.order.service.promo;

import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoDiscountType;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single home for promo discount math (BR-2). Quote and order paths must never
 * implement this twice - they WILL drift.
 */
@UtilityClass
public class PromoPricing {

    public BigDecimal calculateDiscount(PromoCampaign campaign, BigDecimal base) {
        if (base == null) base = BigDecimal.ZERO;
        BigDecimal discount;
        if (campaign.getDiscountType() == PromoDiscountType.PERCENT) {
            discount = base.multiply(campaign.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (campaign.getMaxDiscountAmount() != null
                    && discount.compareTo(campaign.getMaxDiscountAmount()) > 0) {
                discount = campaign.getMaxDiscountAmount();
            }
        } else {
            discount = campaign.getDiscountValue();
        }
        if (discount.compareTo(base) > 0) discount = base;
        return discount.compareTo(BigDecimal.ZERO) > 0 ? discount : BigDecimal.ZERO;
    }
}
