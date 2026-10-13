package az.fitnest.order.service.promo;

import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.model.entity.promo.*;
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
 * - reservePromoForOrder: called when an order is created with a promo code.
 *   Only one parallel order may succeed; the second gets PROMO_RESERVED.
 * - releasePromoReservation: called when payment fails / is cancelled / expired.
 * - redeemPromo: called by the payment outbox inside the SAME transaction that
 *   applies the subscription. A bank callback alone never redeems.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromoOrderService {

    public static final int RESERVATION_MINUTES = 30;

    private final PromoGrantRepository grantRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoGrantService grantService;
    private final PromoEventPublisher eventPublisher;

    /**
     * Reserves the user's grant for the given promo code.
     *
     * @throws BadRequestException PROMO_RESERVED for the second parallel order,
     *         PROMO_ALREADY_USED, PROMO_EXPIRED, PROMO_LIMIT_REACHED
     */
    @Transactional
    public UUID resolveAndReserve(Long userId, String promoCode, UUID planId,
                                  BigDecimal payableAmount, String orderId) {
        if (promoCode == null || promoCode.isBlank()) return null;

        PromoCampaign campaign = campaignRepository.findByCodeNormalized(promoCode.toUpperCase())
                .orElseThrow(() -> new BadRequestException("error.promo_not_eligible"));

        if (campaign.getStatus() != PromoCampaignStatus.ACTIVE) {
            throw new BadRequestException("error.promo_not_active");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (campaign.getEndsAt().isBefore(now)) {
            throw new BadRequestException("error.promo_expired");
        }
        if (campaign.getTotalLimit() != null && campaign.getRedeemedCount() >= campaign.getTotalLimit()) {
            throw new BadRequestException("error.promo_limit_reached");
        }
        if (planId != null && campaign.getPlanIds() != null && campaign.getPlanIds().length > 0
                && !java.util.Arrays.asList(campaign.getPlanIds()).contains(planId)) {
            throw new BadRequestException("error.promo_plan_not_allowed");
        }

        PromoGrant grant = grantRepository.findByCampaignIdAndUserId(campaign.getId(), userId)
                .orElseThrow(() -> new BadRequestException("error.promo_not_eligible"));

        if (grant.getStatus() == PromoGrantStatus.REDEEMED) {
            throw new BadRequestException("error.promo_already_used");
        }
        if (grant.getStatus() != PromoGrantStatus.GRANTED && grant.getStatus() != PromoGrantStatus.RESERVED) {
            throw new BadRequestException("error.promo_not_active");
        }

        try {
            grantService.reserve(campaign.getId(), userId, orderId, now.plusMinutes(RESERVATION_MINUTES));
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("RESERVED")) throw new BadRequestException("error.promo_reserved");
            if (msg.contains("ALREADY_USED")) throw new BadRequestException("error.promo_already_used");
            throw new BadRequestException("error.promo_reserved");
        }
        return campaign.getId();
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
     * Stores the promo snapshot on the order (discount applied at checkout).
     */
    public static PromoSnapshot snapshot(UUID campaignId, String code, BigDecimal discountAmount) {
        return new PromoSnapshot(campaignId, code, discountAmount);
    }

    /**
     * Calculates the discount for a code + base amount, for stamping the order.
     * Returns ZERO when the code does not apply.
     */
    @Transactional(readOnly = true)
    public BigDecimal discountFor(Long userId, String promoCode, BigDecimal baseAmount, Long optionId) {
        if (promoCode == null || promoCode.isBlank()) return BigDecimal.ZERO;
        PromoCampaign campaign = campaignRepository.findByCodeNormalized(promoCode.toUpperCase()).orElse(null);
        if (campaign == null) return BigDecimal.ZERO;
        if (campaign.getStatus() != PromoCampaignStatus.ACTIVE) return BigDecimal.ZERO;

        BigDecimal discount;
        if (campaign.getDiscountType() == PromoDiscountType.PERCENT) {
            discount = baseAmount.multiply(campaign.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
            if (campaign.getMaxDiscountAmount() != null && discount.compareTo(campaign.getMaxDiscountAmount()) > 0) {
                discount = campaign.getMaxDiscountAmount();
            }
        } else {
            discount = campaign.getDiscountValue();
        }
        if (discount.compareTo(baseAmount) > 0) discount = baseAmount;
        return discount.compareTo(BigDecimal.ZERO) > 0 ? discount : BigDecimal.ZERO;
    }

    public record PromoSnapshot(UUID campaignId, String code, BigDecimal discountAmount) {}
}
