package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.*;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoQuoteService {

    private final PromoGrantRepository grantRepository;
    private final PromoCampaignRepository campaignRepository;
    private final PromoCampaignTranslationRepository translationRepository;
    private final PromoEligibilityService eligibilityService;
    private final PromoRateLimitService rateLimitService;

    @Transactional
    public PromoQuoteResponse quote(String planId, String promoCode, boolean useFitCoin, String paymentType, BigDecimal planBasePrice) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) throw new BadRequestException("error.unauthorized");

        // NFR-2: 30 quote calls / user / minute
        if (!rateLimitService.tryConsumeQuote(userId)) {
            throw new BadRequestException("error.promo_rate_limited");
        }

        BigDecimal base = planBasePrice != null ? planBasePrice : BigDecimal.ZERO;
        PromoQuoteResponse.PromoQuoteResponseBuilder builder = PromoQuoteResponse.builder()
                .currency("AZN")
                .baseAmount(base)
                .fitCoinAmount(BigDecimal.ZERO)
                .finalAmount(base);

        if (promoCode == null || promoCode.isBlank()) {
            return builder.build();
        }

        PromoCampaign campaign = campaignRepository.findByCodeNormalized(promoCode.toUpperCase())
                .orElseThrow(() -> {
                    rateLimitService.tryConsumeFailedValidation(userId);
                    return new BadRequestException("error.promo_not_eligible");
                });

        try {
            validateCampaignUsable(campaign, userId, planId, paymentType, useFitCoin);
        } catch (BadRequestException e) {
            rateLimitService.tryConsumeFailedValidation(userId);
            throw e;
        }

        BigDecimal discount = calculateDiscount(campaign, base);

        Optional<PromoGrant> grantOpt = grantRepository.findByCampaignIdAndUserId(campaign.getId(), userId);
        if (grantOpt.isPresent() && grantOpt.get().getStatus() == PromoGrantStatus.RESERVED) {
            throw new BadRequestException("error.promo_reserved");
        }

        final String[] labelHolder = {"endirim"};
        translationRepository.findByCampaignId(campaign.getId()).stream()
                .filter(t -> t.getLocale() == PromoLocale.az)
                .findFirst()
                .ifPresent(t -> labelHolder[0] = t.getCouponLabel() != null ? t.getCouponLabel()
                        : (t.getCardLabel() != null ? t.getCardLabel() : labelHolder[0]));

        BigDecimal finalAmount = base.subtract(discount);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) finalAmount = BigDecimal.ZERO;

        return builder
                .promo(PromoQuotePromoResponse.builder()
                        .code(campaign.getCode())
                        .campaignId(campaign.getId())
                        .label(labelHolder[0])
                        .discountAmount(discount)
                        .build())
                .finalAmount(finalAmount)
                .build();
    }

    private void validateCampaignUsable(PromoCampaign c, Long userId, String planId, String paymentType, boolean useFitCoin) {
        if (c.getStatus() != PromoCampaignStatus.ACTIVE) {
            throw new BadRequestException("error.promo_not_active");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (c.getEndsAt().isBefore(now)) throw new BadRequestException("error.promo_expired");
        if (c.getStartsAt().isAfter(now)) throw new BadRequestException("error.promo_not_active");

        if (c.getTotalLimit() != null && c.getRedeemedCount() >= c.getTotalLimit()) {
            throw new BadRequestException("error.promo_limit_reached");
        }
        if (planId != null && c.getPlanIds() != null && c.getPlanIds().length > 0
                && !Arrays.asList(c.getPlanIds()).contains(planId)) {
            throw new BadRequestException("error.promo_plan_not_allowed");
        }

        // grant must exist for the user (server-side eligibility)
        Optional<PromoGrant> grant = grantRepository.findByCampaignIdAndUserId(c.getId(), userId);
        if (grant.isEmpty()) {
            throw new BadRequestException("error.promo_not_eligible");
        }
        PromoGrant g = grant.get();
        if (g.getStatus() == PromoGrantStatus.REDEEMED) {
            throw new BadRequestException("error.promo_already_used");
        }
        if (g.getStatus() != PromoGrantStatus.GRANTED && g.getStatus() != PromoGrantStatus.RESERVED) {
            throw new BadRequestException("error.promo_not_active");
        }

        if (!Boolean.TRUE.equals(c.getAllowFitcoin()) && useFitCoin) {
            throw new BadRequestException("error.promo_payment_type_not_allowed");
        }
        if (paymentType != null && paymentType.equalsIgnoreCase("INSTALLMENT") && !Boolean.TRUE.equals(c.getAllowInstallments())) {
            throw new BadRequestException("error.promo_payment_type_not_allowed");
        }
    }

    private BigDecimal calculateDiscount(PromoCampaign c, BigDecimal base) {
        BigDecimal discount;
        if (c.getDiscountType() == PromoDiscountType.PERCENT) {
            discount = base.multiply(c.getDiscountValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (c.getMaxDiscountAmount() != null && discount.compareTo(c.getMaxDiscountAmount()) > 0) {
                discount = c.getMaxDiscountAmount();
            }
        } else {
            discount = c.getDiscountValue();
        }
        if (discount.compareTo(base) > 0) discount = base;
        return discount;
    }
}
