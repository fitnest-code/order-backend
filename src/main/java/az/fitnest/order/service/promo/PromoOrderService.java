package az.fitnest.order.service.promo;

import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoCampaignStatus;
import az.fitnest.order.model.entity.promo.PromoGrant;
import az.fitnest.order.model.entity.promo.PromoGrantStatus;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import az.fitnest.order.repository.promo.PromoGrantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Integrates promo grants with the order/payment lifecycle (FR-RED-1, 13.3).
 *
 * - {@link #resolveAndReserve}: called when an order is created with a promo code.
 *   Only one parallel order may succeed; the second gets PROMO_RESERVED.
 * - {@link #releasePromoReservation}: called when payment fails / is cancelled / expired.
 * - {@link #redeemPromo}: called by the payment outbox inside the SAME transaction
 *   that applies the subscription. A bank callback alone never redeems.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromoOrderService {

    public static final int RESERVATION_MINUTES = 30;

    private final PromoGrantRepository grantRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoGrantService grantService;

    /**
     * Reserves the user's grant for the given promo code.
     *
     * @throws BadRequestException PROMO_RESERVED for the second parallel order,
     *         PROMO_ALREADY_USED, PROMO_EXPIRED, PROMO_LIMIT_REACHED,
     *         PROMO_PAYMENT_TYPE_NOT_ALLOWED
     */
    @Transactional
    public UUID resolveAndReserve(Long userId, String promoCode, String packageId,
                                  boolean useFitCoin, String paymentType, String orderId) {
        if (promoCode == null || promoCode.isBlank()) return null;

        PromoCampaign campaign = campaignRepository.findByCodeNormalized(promoCode.toUpperCase())
                .orElseThrow(() -> new BadRequestException("error.promo_not_eligible"));

        PromoGrant grant = grantService.assertGrantUsable(campaign.getId(), userId, packageId);
        assertPaymentRules(campaign, useFitCoin, paymentType);

        try {
            grantService.reserve(campaign.getId(), userId, orderId,
                    OffsetDateTime.now().plusMinutes(RESERVATION_MINUTES));
        } catch (PromoReservationException e) {
            throw new BadRequestException(e.getErrorCode());
        }
        return campaign.getId();
    }

    private void assertPaymentRules(PromoCampaign campaign, boolean useFitCoin, String paymentType) {
        if (!Boolean.TRUE.equals(campaign.getAllowFitcoin()) && useFitCoin) {
            throw new BadRequestException("error.promo_payment_type_not_allowed");
        }
        if (paymentType != null && paymentType.equalsIgnoreCase("INSTALLMENT")
                && !Boolean.TRUE.equals(campaign.getAllowInstallments())) {
            throw new BadRequestException("error.promo_payment_type_not_allowed");
        }
    }

    @Transactional
    public void releasePromoReservation(String orderId) {
        if (orderId == null) return;
        List<PromoGrant> grants = grantRepository.findByReservedOrderIdAndStatus(orderId, PromoGrantStatus.RESERVED);
        for (PromoGrant g : grants) {
            grantService.release(g.getId());
        }
        if (!grants.isEmpty()) {
            log.info("Released {} promo reservations for order {}", grants.size(), orderId);
        }
    }

    /**
     * Redeems the grant inside the payment outbox transaction.
     * Idempotent: a grant already REDEEMED is left untouched.
     */
    @Transactional
    public void redeemPromo(String orderId, String paymentId,
                            BigDecimal baseAmount, BigDecimal discountAmount, BigDecimal finalAmount) {
        if (orderId == null) return;
        List<PromoGrant> grants = grantRepository.findByAnyOrderId(orderId);
        for (PromoGrant g : grants) {
            grantService.redeem(g.getId(), orderId, paymentId, baseAmount, discountAmount, finalAmount);
        }
    }

    /**
     * Discount for a code + base amount, for stamping the order.
     * Returns ZERO when the code does not apply.
     */
    @Transactional(readOnly = true)
    public BigDecimal discountFor(String promoCode, BigDecimal baseAmount) {
        if (promoCode == null || promoCode.isBlank()) return BigDecimal.ZERO;
        PromoCampaign campaign = campaignRepository.findByCodeNormalized(promoCode.toUpperCase()).orElse(null);
        if (campaign == null || campaign.getStatus() != PromoCampaignStatus.ACTIVE) return BigDecimal.ZERO;
        return PromoPricing.calculateDiscount(campaign, baseAmount);
    }
}
