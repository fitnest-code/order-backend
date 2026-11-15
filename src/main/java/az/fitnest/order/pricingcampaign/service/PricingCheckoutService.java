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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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

    /**
     * v2 contract is frozen (product rule: never edit published endpoints in place): Epoint only.
     * Multi-rail (ABB/BOB) lives in {@link #createV3}.
     */
    @Transactional
    public CheckoutCreateResponseV2 create(Long userId, CheckoutCreateRequestV2 req) {
        return createInternal(userId, req.packageId(), req.optionId(), req.useFitCoin(), req.is_installment(),
                req.expectedFinalAmount(), V2_EPOINT_ROUTE);
    }

    /** v3: same quote/charge rules as v2, plus paymentType/installmentProvider routing to Epoint, ABB or BOB. */
    @Transactional
    public CheckoutCreateResponseV2 createV3(Long userId, CheckoutCreateRequestV3 req) {
        // Validate the rail before any pricing/wallet work so bad combos fail fast and touch nothing.
        Route route = resolveRoute(req);
        return createInternal(userId, req.packageId(), req.optionId(), req.useFitCoin(), req.is_installment(),
                req.expectedFinalAmount(), route);
    }

    private CheckoutCreateResponseV2 createInternal(Long userId, Long packageId, Long optionId, Boolean useFitCoin,
                                                    Integer isInstallment, BigDecimal expectedFinalAmount, Route route) {
        Quote q = quote(userId, packageId, optionId, Boolean.TRUE.equals(useFitCoin));
        PricingDto pricing = q.pricing();
        // Product rule: preview total == bank charge. FitCoin is applied AFTER campaign discount.
        BigDecimal payable = q.total();

        if (expectedFinalAmount != null && expectedFinalAmount.compareTo(payable) != 0) {
            throw new ConflictException("error.price_changed");
        }
        // Epoint (and CreatePayment) require a positive amount; full-coin cover is a separate payment flow.
        if (payable.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("error.price_changed");
        }

        // ABB needs a numeric ORDER (6-32 digits); Epoint/BOB take ord_*.
        String orderId = route.provider() == Provider.ABB ? numericOrderId()
                : "ord_" + UUID.randomUUID().toString().substring(0, 8);
        String packageRef = "packageId:" + q.pkg().getId() + ",optionId:" + q.option().getId()
                + (pricing.campaignApplied() ? ",campaignId:" + pricing.campaignId() : "");

        Double coinsUsed = q.fitCoin().used() && q.fitCoin().usedCoins() != null
                ? q.fitCoin().usedCoins().doubleValue() : null;

        EpointResponse response;
        try {
            response = paymentGrpcClient.initiatePayment(EpointPaymentRequest.builder()
                    .order_id(orderId)
                    .amount(payable.doubleValue())
                    .currency(currency(q.pkg()))
                    .description(q.pkg().getName() + " - " + q.option().getDurationMonths() + " months")
                    .language("az")
                    // is_installment is the Epoint CARD widget flag only; never forwarded to ABB/BOB.
                    .is_installment(route.provider() == Provider.EPOINT && isInstallment != null ? isInstallment : 0)
                    .refund(0)
                    .other_attr(Collections.singletonList(packageRef))
                    .prePriced(true)
                    .userId(userId)
                    .coinsUsed(coinsUsed)
                    // null keeps the v2 gRPC request byte-identical to before; payment-backend defaults to epoint.
                    .provider(route.sendProvider() ? route.provider().wire : null)
                    .installmentMonths(route.months())
                    .cardId(route.cardId())
                    .build());
        } catch (StatusRuntimeException e) {
            // Payment-backend rejects unsupported months / package refs with INVALID_ARGUMENT.
            if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                throw new BadRequestException("error.installment_not_supported");
            }
            throw e;
        }
        if (response == null || !chargeAccepted(route, response)) {
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

        CheckoutPaymentInfoDto paymentInfo;
        if (route.provider() == Provider.APPLE_PAY || route.provider() == Provider.GOOGLE_PAY) {
            // Both wallets run on a pre-priced Epoint widget (payment-backend returns the widget URL as
            // redirect_url): iOS opens it in a WKWebView, so paymentUrl is required. Native create/submit
            // is not used for BF because the legacy */create endpoints re-quote at list price and lose the
            // discount. Transaction id stays in paymentIntentClientSecret only as a debugging aid.
            paymentInfo = CheckoutPaymentInfoDto.builder().provider(route.provider().wire)
                    .paymentIntentClientSecret(response.transaction()).paymentUrl(response.redirect_url())
                    .status(response.status()).build();
        } else if (route.provider() == Provider.SAVED_CARD) {
            // Saved card charges server-side: immediate success has no URL; a bank 3DS challenge returns
            // redirect_url. Blank -> null so mobile can branch on paymentUrl == null, then poll by orderId.
            String url = response.redirect_url() == null || response.redirect_url().isBlank()
                    ? null : response.redirect_url();
            paymentInfo = CheckoutPaymentInfoDto.builder().provider(route.provider().wire)
                    .paymentUrl(url).status(response.status()).build();
        } else {
            paymentInfo = CheckoutPaymentInfoDto.builder().provider(route.provider().wire)
                    .paymentUrl(response.redirect_url()).status(response.status()).build();
        }
        return new CheckoutCreateResponseV2(orderId, "pending_payment", payable, currency(q.pkg()),
                paymentInfo, pricing.campaignApplied(), pricing.campaignId());
    }

    /**
     * Every rail except SAVED_CARD only accepts a literal "success" at initiation (the user still has to pay in a
     * redirect/wallet). A saved-card charge happens inside initiation, so besides "success" the bank may answer
     * with a 3DS challenge (redirect_url / "new"); only explicit failures abort the order.
     */
    private static boolean chargeAccepted(Route route, EpointResponse response) {
        if ("success".equalsIgnoreCase(response.status())) {
            return true;
        }
        if (route.provider() != Provider.SAVED_CARD || response.status() == null) {
            return false;
        }
        String s = response.status().toLowerCase();
        return !(s.equals("error") || s.equals("failed") || s.equals("server_error") || s.equals("declined"));
    }

    private enum Provider {
        EPOINT("epoint"), ABB("abb"), BOB("bob"), APPLE_PAY("apple_pay"), GOOGLE_PAY("google_pay"),
        SAVED_CARD("saved_card");
        final String wire;
        Provider(String wire) { this.wire = wire; }
    }

    /** sendProvider=false only for legacy v2, which never set a provider on the gRPC request. */
    private record Route(Provider provider, Integer months, boolean sendProvider, String cardId) {
        Route(Provider provider, Integer months, boolean sendProvider) {
            this(provider, months, sendProvider, null);
        }
    }

    private static final Route V2_EPOINT_ROUTE = new Route(Provider.EPOINT, null, false);

    /**
     * Maps paymentType/installmentProvider to a payment rail. CARD/omitted stays on Epoint; APPLE_PAY / GOOGLE_PAY are
     * native Epoint token flows. SAVED_CARD charges the user's Epoint card list entry with the BF total; a
     * missing cardId fails loudly instead of silently falling back to a card charge the user did not choose.
     */
    private static Route resolveRoute(CheckoutCreateRequestV3 req) {
        String type = req.paymentType() == null || req.paymentType().isBlank()
                ? "CARD" : req.paymentType().trim().toUpperCase();
        switch (type) {
            case "CARD":
                return new Route(Provider.EPOINT, null, true);
            case "INSTALLMENT":
                String p = req.installmentProvider() == null ? "" : req.installmentProvider().trim().toUpperCase();
                Integer months = req.installmentMonths();
                if (months == null || months <= 0) {
                    throw new BadRequestException("error.installment_months_required");
                }
                return switch (p) {
                    case "TAMKART" -> new Route(Provider.ABB, months, true);
                    case "BOLKART" -> new Route(Provider.BOB, months, true);
                    default -> throw new BadRequestException("error.installment_provider_invalid");
                };
            case "APPLE_PAY":
                return new Route(Provider.APPLE_PAY, null, true);
            case "GOOGLE_PAY":
                return new Route(Provider.GOOGLE_PAY, null, true);
            case "SAVED_CARD":
                if (req.cardId() == null || req.cardId().isBlank()) {
                    throw new BadRequestException("error.card_id_required");
                }
                return new Route(Provider.SAVED_CARD, null, true, req.cardId().trim());
            default:
                throw new BadRequestException("error.payment_type_invalid");
        }
    }

    /** ABB spec: ORDER is 6-32 digits, so BF ABB orders cannot use the ord_* format. 17 digits like ABB's own generator. */
    private static String numericOrderId() {
        return System.currentTimeMillis() + String.valueOf(ThreadLocalRandom.current().nextInt(1000, 10000));
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
