package az.fitnest.order.service.impl;

import az.fitnest.order.service.CheckoutService;
import az.fitnest.order.dto.CheckoutRequest;
import az.fitnest.order.dto.CheckoutResponse;
import az.fitnest.order.dto.CheckoutPaymentInfoDto;
import az.fitnest.order.model.entity.Order;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.OrderRepository;
import az.fitnest.order.service.promo.PromoOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutServiceImpl implements CheckoutService {

    private final SubscriptionPackageRepository packageRepository;
    private final OrderRepository orderRepository;
    private final az.fitnest.order.grpc.PaymentGrpcClient paymentGrpcClient;
    private final PromoOrderService promoOrderService;

    @Transactional
    @Override
    public CheckoutResponse processCheckout(Long userId, CheckoutRequest request) {
        // B13: camelCase aliases accepted; snake_case kept for old clients.
        String packageIdStr = request.packageId() != null ? request.packageId() : request.package_id();
        Long optionId = request.optionId() != null ? request.optionId() : request.option_id();
        Long packageId = Long.parseLong(packageIdStr);
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .filter(SubscriptionPackage::getIsActive)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        PackageOption option = pkg.getOptions().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.duration_config_not_found"));
        BigDecimal baseAmount = option.getPriceDiscounted() != null ? option.getPriceDiscounted() : option.getPriceStandard();
        String orderId = "ord_" + UUID.randomUUID().toString().substring(0, 8);
        String language = "az";

        // ---- Promo code handling (FR-RED-1, 13.3) ----
        // Reserve the user's grant so that two parallel orders cannot both
        // consume the same grant. The bank callback alone never redeems.
        UUID promoCampaignId = null;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal payable = baseAmount;

        if (request.promoCode() != null && !request.promoCode().isBlank()) {
            boolean useFitCoin = Boolean.TRUE.equals(request.useFitCoin());
            promoCampaignId = promoOrderService.resolveAndReserve(
                    userId, request.promoCode(), packageIdStr, useFitCoin, request.paymentType(), orderId);

            discount = promoOrderService.discountFor(request.promoCode(), baseAmount);
            payable = baseAmount.subtract(discount);
            if (payable.compareTo(BigDecimal.ZERO) < 0) payable = BigDecimal.ZERO;
        }

        // PRICE_CHANGED: the price the user saw is not what we will charge
        if (request.expectedFinalAmount() != null
                && request.expectedFinalAmount().compareTo(payable) != 0) {
            throw new az.fitnest.order.exception.ConflictException("error.price_changed");
        }

        az.fitnest.order.dto.epoint.EpointPaymentRequest paymentRequest = az.fitnest.order.dto.epoint.EpointPaymentRequest.builder()
                .order_id(orderId)
                .amount(payable.doubleValue())
                .currency(pkg.getCurrency())
                .description(pkg.getName() + " - " + option.getDurationMonths() + " months")
                .language(language)
                .is_installment(request.is_installment() != null ? request.is_installment() : 0)
                .refund(0)
                .other_attr(java.util.Collections.emptyList())
                .build();
        az.fitnest.order.dto.epoint.EpointResponse epointResponse = paymentGrpcClient.initiatePayment(paymentRequest);
        if (epointResponse == null || !"success".equalsIgnoreCase(epointResponse.status())) {
            // release the reservation; the user can retry with the code
            promoOrderService.releasePromoReservation(orderId);
            throw new RuntimeException("Payment initiation failed: " + (epointResponse != null ? epointResponse.message() : "Unknown error"));
        }

        // Persist the order with promo columns in the price snapshot
        Order order = Order.builder()
                .orderId(orderId)
                .userId(userId)
                .type("subscription_purchase")
                .status("PENDING")
                .amount(payable)
                .currency(pkg.getCurrency())
                .createdAt(LocalDateTime.now())
                .providerReference(epointResponse.redirect_url())
                .promoCampaignId(promoCampaignId)
                .promoCode(request.promoCode())
                .promoDiscountAmount(discount.compareTo(BigDecimal.ZERO) > 0 ? discount : null)
                .build();
        try {
            orderRepository.save(order);
        } catch (Exception e) {
            log.warn("Could not persist order snapshot for {}: {}", orderId, e.getMessage());
        }

        return CheckoutResponse.builder()
                .orderId(orderId)
                .status("pending_payment")
                .amount(payable.doubleValue())
                .currency(pkg.getCurrency())
                .payment(CheckoutPaymentInfoDto.builder()
                        .provider("epoint")
                        .paymentUrl(epointResponse.redirect_url())
                        .status(epointResponse.status())
                        .build())
                .build();
    }
}
