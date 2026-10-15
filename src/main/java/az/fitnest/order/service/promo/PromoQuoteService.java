package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.*;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.SubscriptionPackageRepository;
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
import java.util.List;
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
    private final SubscriptionPackageRepository packageRepository;
    private final az.fitnest.order.grpc.PaymentGrpcClient paymentGrpcClient;

    /**
     * B12: price is resolved server-side from packageId+optionId ("Never price
     * on the client"). B13: identifiers match checkout (package_id/option_id).
     */
    @Transactional
    public PromoQuoteResponse quote(String packageId, Long optionId, String promoCode,
                                    boolean useFitCoin, String paymentType,
                                    String installmentProvider, Integer installmentMonths) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) throw new BadRequestException("error.unauthorized");

        // NFR-2: 30 quote calls / user / minute
        if (!rateLimitService.tryConsumeQuote(userId)) {
            throw new BadRequestException("error.promo_rate_limited");
        }

        if (packageId == null || packageId.isBlank()) throw new BadRequestException("error.plan_not_found");
        Long pkgId;
        try {
            pkgId = Long.parseLong(packageId.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("error.plan_not_found");
        }
        SubscriptionPackage pkg = packageRepository.findById(pkgId)
                .filter(SubscriptionPackage::getIsActive)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        PackageOption option = pkg.getOptions().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.duration_config_not_found"));
        BigDecimal base = option.getPriceDiscounted() != null ? option.getPriceDiscounted() : option.getPriceStandard();
        if (base == null) base = BigDecimal.ZERO;

        PromoQuoteResponse.PromoQuoteResponseBuilder builder = PromoQuoteResponse.builder()
                .currency(pkg.getCurrency() != null ? pkg.getCurrency() : "AZN")
                .packageId(packageId)
                .optionId(optionId)
                .baseAmount(base)
                .fitCoinAmount(BigDecimal.ZERO)
                .finalAmount(base);

        // FitCoin wallet is always reported (B10) so the app can render
        // "Mövcud balans: X Coin (Y AZN)" without a second call.
        var wallet = paymentGrpcClient.getCoinWallet(userId);
        BigDecimal balanceCoins = wallet.coinBalance() != null ? wallet.coinBalance() : BigDecimal.ZERO;
        BigDecimal balanceAzn = wallet.aznEquivalent() != null ? wallet.aznEquivalent() : BigDecimal.ZERO;

        BigDecimal afterPromo = base;
        PromoQuotePromoResponse promoPart = null;

        if (promoCode != null && !promoCode.isBlank()) {
            PromoCampaign campaign = campaignRepository.findByCodeNormalized(promoCode.toUpperCase())
                    .orElseThrow(() -> {
                        rateLimitService.tryConsumeFailedValidation(userId);
                        return new BadRequestException("error.promo_not_eligible");
                    });

            try {
                validateCampaignUsable(campaign, userId, packageId, paymentType, useFitCoin,
                        installmentProvider);
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

            afterPromo = base.subtract(discount);
            if (afterPromo.compareTo(BigDecimal.ZERO) < 0) afterPromo = BigDecimal.ZERO;

            promoPart = PromoQuotePromoResponse.builder()
                    .code(campaign.getCode())
                    .campaignId(campaign.getId())
                    .label(labelHolder[0])
                    .discountAmount(discount)
                    .build();
            builder.promo(promoPart);
        }

        // FitCoin applies AFTER promo (40 - coins). Promo + FitCoin combine only
        // when the campaign allows it (allow_fitcoin).
        BigDecimal usedAmount = BigDecimal.ZERO;
        BigDecimal usedCoins = BigDecimal.ZERO;
        if (useFitCoin && balanceAzn.compareTo(BigDecimal.ZERO) > 0 && afterPromo.compareTo(BigDecimal.ZERO) > 0) {
            usedAmount = balanceAzn.min(afterPromo);
            if (balanceCoins.compareTo(BigDecimal.ZERO) > 0) {
                usedCoins = usedAmount.multiply(balanceCoins)
                        .divide(balanceAzn, 0, RoundingMode.DOWN);
            }
        }
        BigDecimal finalAmount = afterPromo.subtract(usedAmount);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) finalAmount = BigDecimal.ZERO;

        builder.fitCoinAmount(usedAmount)
                .fitCoin(PromoQuoteFitCoinResponse.builder()
                        .balanceCoins(balanceCoins)
                        .balanceAmount(balanceAzn)
                        .usedCoins(usedCoins)
                        .usedAmount(usedAmount)
                        .build())
                .finalAmount(finalAmount);

        // B9: installments echo provider/months with server-side monthly math.
        // Promo + installment combine only when the campaign allows installments.
        if (paymentType != null && paymentType.equalsIgnoreCase("INSTALLMENT")) {
            if (installmentMonths == null || installmentMonths <= 0) {
                throw new BadRequestException("error.promo_installment_months_required");
            }
            BigDecimal monthly = finalAmount.divide(BigDecimal.valueOf(installmentMonths), 2, RoundingMode.HALF_UP);
            builder.installments(PromoInstallmentsResponse.builder()
                    .provider(installmentProvider)
                    .options(List.of(PromoInstallmentOptionResponse.builder()
                            .months(installmentMonths)
                            .monthlyAmount(monthly)
                            .totalAmount(finalAmount)
                            .bank(installmentProvider)
                            .build()))
                    .monthlyAmount(monthly)
                    .months(installmentMonths)
                    .totalAmount(finalAmount)
                    .build());
        }

        return builder.build();
    }

    /** planId-based entry kept for old query-param callers (planId == packageId). */
    @Transactional
    public PromoQuoteResponse quote(String planId, String promoCode, boolean useFitCoin,
                                    String paymentType, BigDecimal ignoredBasePrice) {
        return quote(planId, null, promoCode, useFitCoin, paymentType, null, null);
    }

    private void validateCampaignUsable(PromoCampaign c, Long userId, String packageId, String paymentType,
                                        boolean useFitCoin, String installmentProvider) {
        if (c.getStatus() != PromoCampaignStatus.ACTIVE) {
            throw new BadRequestException("error.promo_not_active");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (c.getEndsAt().isBefore(now)) throw new BadRequestException("error.promo_expired");
        if (c.getStartsAt().isAfter(now)) throw new BadRequestException("error.promo_not_active");

        if (c.getTotalLimit() != null && c.getRedeemedCount() >= c.getTotalLimit()) {
            throw new BadRequestException("error.promo_limit_reached");
        }
        if (packageId != null && c.getPlanIds() != null && c.getPlanIds().length > 0
                && !Arrays.asList(c.getPlanIds()).contains(packageId)) {
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
        if (paymentType != null && paymentType.equalsIgnoreCase("INSTALLMENT")) {
            if (!Boolean.TRUE.equals(c.getAllowInstallments())) {
                throw new BadRequestException("error.promo_payment_type_not_allowed");
            }
            if (installmentProvider != null
                    && !installmentProvider.equalsIgnoreCase("TAMKART")
                    && !installmentProvider.equalsIgnoreCase("BOLKART")) {
                throw new BadRequestException("error.promo_installment_provider_invalid");
            }
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
