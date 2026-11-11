package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.dto.CheckoutPaymentInfoDto;
import az.fitnest.order.dto.epoint.EpointPaymentRequest;
import az.fitnest.order.dto.epoint.EpointResponse;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ConflictException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.grpc.PaymentGrpcClient;
import az.fitnest.order.model.entity.Order;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.pricingcampaign.dto.*;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.repository.OrderRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingCheckoutService {

    private final SubscriptionPackageRepository packageRepository;
    private final OrderRepository orderRepository;
    private final PaymentGrpcClient paymentGrpcClient;
    private final PricingCampaignService campaignService;

    /** Everything both endpoints need, computed once so preview and create can never disagree. */
    private record Quote(SubscriptionPackage pkg, PackageOption option, PricingDto pricing,
                         CheckoutPreviewResponseV2.FitCoinPart fitCoin, BigDecimal total) {}

    @Transactional(readOnly = true)
    public CheckoutPreviewResponseV2 preview(Long userId, CheckoutPreviewRequestV2 req) {
        Quote q = quote(userId, req.packageId(), req.optionId(), Boolean.TRUE.equals(req.useFitCoin()));
        return new CheckoutPreviewResponseV2(q.pricing().listPrice(), q.pricing(), q.fitCoin(), q.total(),
                q.pricing().campaignApplied(), currency(q.pkg()));
    }

    @Transactional
    public CheckoutCreateResponseV2 create(Long userId, CheckoutCreateRequestV2 req) {
        Quote q = quote(userId, req.packageId(), req.optionId(), Boolean.TRUE.equals(req.useFitCoin()));
        PricingDto pricing = q.pricing();
        // Product rule: preview total == bank charge. FitCoin is applied AFTER campaign discount.
        BigDecimal payable = q.total();

        if (req.expectedFinalAmount() != null && req.expectedFinalAmount().compareTo(payable) != 0) {
            throw new ConflictException("error.price_changed");
        }
        // Epoint (and CreatePayment) require a positive amount; full-coin cover is a separate payment flow.
        if (payable.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("error.price_changed");
        }

        String orderId = "ord_" + UUID.randomUUID().toString().substring(0, 8);
        String packageRef = "packageId:" + q.pkg().getId() + ",optionId:" + q.option().getId()
                + (pricing.campaignApplied() ? ",campaignId:" + pricing.campaignId() : "");

        Double coinsUsed = q.fitCoin().used() && q.fitCoin().usedCoins() != null
                ? q.fitCoin().usedCoins().doubleValue() : null;

        EpointResponse response = paymentGrpcClient.initiatePayment(EpointPaymentRequest.builder()
                .order_id(orderId)
                .amount(payable.doubleValue())
                .currency(currency(q.pkg()))
                .description(q.pkg().getName() + " - " + q.option().getDurationMonths() + " months")
                .language("az")
                .is_installment(req.is_installment() != null ? req.is_installment() : 0)
                .refund(0)
                .other_attr(Collections.singletonList(packageRef))
                .prePriced(true)
                .userId(userId)
                .coinsUsed(coinsUsed)
                .build());
        if (response == null || !"success".equalsIgnoreCase(response.status())) {
            throw new RuntimeException("Payment initiation failed: " + (response != null ? response.message() : "Unknown error"));
        }

        Order order = Order.builder()
                .orderId(orderId).userId(userId).type("subscription_purchase").status("PENDING")
                .amount(payable).currency(currency(q.pkg())).createdAt(LocalDateTime.now())
                .providerReference(response.redirect_url())
                .pricingCampaignId(pricing.campaignApplied() ? pricing.campaignId() : null)
                .pricingDiscountPercent(pricing.campaignApplied() ? pricing.discountPercent() : null)
                .pricingDiscountAmount(pricing.campaignApplied() ? pricing.savedAmount() : null)
                .build();
        try {
            orderRepository.save(order);
        } catch (Exception e) {
            log.warn("Could not persist order snapshot for {}: {}", orderId, e.getMessage());
        }

        return new CheckoutCreateResponseV2(orderId, "pending_payment", payable, currency(q.pkg()),
                CheckoutPaymentInfoDto.builder().provider("epoint").paymentUrl(response.redirect_url())
                        .status(response.status()).build(),
                pricing.campaignApplied(), pricing.campaignId());
    }

    private Quote quote(Long userId, Long packageId, Long optionId, boolean useFitCoin) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .filter(SubscriptionPackage::getIsActive)
                .orElseThrow(() -> new ResourceNotFoundException("error.plan_not_found"));
        PackageOption option = pkg.getOptions().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("error.duration_config_not_found"));
        // List price is priceStandard ONLY; priceDiscounted must never leak into campaign pricing.
        if (option.getPriceStandard() == null || option.getDurationMonths() == null) {
            throw new BadRequestException("error.duration_config_not_found");
        }
        PricingCampaign campaign = campaignService.applicableCampaign(userId).orElse(null);
        PricingDto pricing = campaignService.computePricing(option.getPriceStandard(), option.getDurationMonths(), campaign);
        CheckoutPreviewResponseV2.FitCoinPart fitCoin = fitCoin(userId, useFitCoin, pricing.finalPrice());
        BigDecimal total = pricing.finalPrice().subtract(fitCoin.usedAmount());
        return new Quote(pkg, option, pricing, fitCoin, total.max(BigDecimal.ZERO));
    }

    /** Wallet is only queried when asked for; FitCoin applies AFTER the campaign discount. */
    private CheckoutPreviewResponseV2.FitCoinPart fitCoin(Long userId, boolean useFitCoin, BigDecimal afterCampaign) {
        if (!useFitCoin) {
            return new CheckoutPreviewResponseV2.FitCoinPart(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        var wallet = paymentGrpcClient.getCoinWallet(userId);
        BigDecimal coins = wallet.coinBalance() != null ? wallet.coinBalance() : BigDecimal.ZERO;
        BigDecimal azn = wallet.aznEquivalent() != null ? wallet.aznEquivalent() : BigDecimal.ZERO;
        BigDecimal usedAmount = PricingMath.fitCoinUsedAmount(afterCampaign, azn);
        BigDecimal usedCoins = usedAmount.signum() > 0 && azn.signum() > 0
                ? usedAmount.multiply(coins).divide(azn, 0, RoundingMode.DOWN) : BigDecimal.ZERO;
        return new CheckoutPreviewResponseV2.FitCoinPart(usedAmount.signum() > 0, coins, azn, usedCoins, usedAmount);
    }

    private static String currency(SubscriptionPackage pkg) {
        return pkg.getCurrency();
    }
}
