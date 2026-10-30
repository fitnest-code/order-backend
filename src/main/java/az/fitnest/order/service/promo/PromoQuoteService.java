package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.*;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.grpc.PaymentGrpcClient;
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
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromoQuoteService {

    private final PromoCampaignRepository campaignRepository;
    private final PromoCampaignTranslationRepository translationRepository;
    private final PromoRateLimitService rateLimitService;
    private final SubscriptionPackageRepository packageRepository;
    private final PaymentGrpcClient paymentGrpcClient;
    private final PromoGrantService grantService;

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
                .orElseThrow(() -> new ResourceNotFoundException("error.plan_not_found"));
        PackageOption option = pkg.getOptions().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("error.duration_config_not_found"));
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

            PromoGrant grant;
            try {
                grant = grantService.assertGrantUsable(campaign.getId(), userId, packageId);
                validatePaymentRules(campaign, useFitCoin, paymentType, installmentProvider, installmentMonths);
            } catch (BadRequestException e) {
                rateLimitService.tryConsumeFailedValidation(userId);
                throw e;
            }

            if (grant.getStatus() == PromoGrantStatus.RESERVED) {
                throw new BadRequestException("error.promo_reserved");
            }

            BigDecimal discount = PromoPricing.calculateDiscount(campaign, base);

            String label = translationRepository.findByCampaignId(campaign.getId()).stream()
                    .filter(t -> t.getLocale() == PromoLocale.az)
                    .findFirst()
                    .map(t -> t.getCouponLabel() != null ? t.getCouponLabel()
                            : (t.getCardLabel() != null ? t.getCardLabel() : "endirim"))
                    .orElse("endirim");

            afterPromo = base.subtract(discount);
            if (afterPromo.compareTo(BigDecimal.ZERO) < 0) afterPromo = BigDecimal.ZERO;

            promoPart = PromoQuotePromoResponse.builder()
                    .code(campaign.getCode())
                    .campaignId(campaign.getId())
                    .label(label)
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

    private void validatePaymentRules(PromoCampaign campaign, boolean useFitCoin, String paymentType,
                                      String installmentProvider, Integer installmentMonths) {
        if (!Boolean.TRUE.equals(campaign.getAllowFitcoin()) && useFitCoin) {
            throw new BadRequestException("error.promo_payment_type_not_allowed");
        }
        if (paymentType != null && paymentType.equalsIgnoreCase("INSTALLMENT")) {
            if (!Boolean.TRUE.equals(campaign.getAllowInstallments())) {
                throw new BadRequestException("error.promo_payment_type_not_allowed");
            }
            if (installmentProvider != null
                    && !installmentProvider.equalsIgnoreCase("TAMKART")
                    && !installmentProvider.equalsIgnoreCase("BOLKART")) {
                throw new BadRequestException("error.promo_installment_provider_invalid");
            }
            if (installmentMonths == null || installmentMonths <= 0) {
                throw new BadRequestException("error.promo_installment_months_required");
            }
        }
    }
}
