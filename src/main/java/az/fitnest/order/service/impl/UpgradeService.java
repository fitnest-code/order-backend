package az.fitnest.order.service.impl;

import az.fitnest.order.dto.*;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.exception.ServiceException;
import az.fitnest.order.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UpgradeService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPackageRepository packageRepository;
    private final MockPaymentService paymentService;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public UpgradeOptionsResponse getUpgradeOptions(Long userId, Integer targetDurationMonths) {
        List<Subscription> activeSubs = subscriptionRepository.findByUserIdAndStatus(userId, "ACTIVE");
        if (activeSubs.isEmpty()) {
            throw new ServiceException("error.no_active_subscription",
                    "NO_ACTIVE_SUBSCRIPTION", org.springframework.http.HttpStatus.CONFLICT);
        }
        Subscription currentSub = activeSubs.stream()
                .max((a, b) -> {
                    if (a.getEndAt() != null && b.getEndAt() != null) {
                        return a.getEndAt().compareTo(b.getEndAt());
                    } else if (a.getEndAt() != null) {
                        return 1;
                    } else if (b.getEndAt() != null) {
                        return -1;
                    } else if (a.getStartAt() != null && b.getStartAt() != null) {
                        return a.getStartAt().compareTo(b.getStartAt());
                    } else {
                        return 0;
                    }
                })
                .get();

        SubscriptionPackage currentPackage = packageRepository.findById(currentSub.getPackageId())
                .orElseThrow(() -> new ServiceException("error.plan_not_found",
                        "INTERNAL_ERROR", org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        long currentDurationLong = 1;
        if (currentSub.getEndAt() != null) {
            currentDurationLong = java.time.temporal.ChronoUnit.MONTHS.between(currentSub.getStartAt(), currentSub.getEndAt());
            if (currentDurationLong == 0) currentDurationLong = 1;
        }
        int currentDuration = (int) currentDurationLong;

        PackageOption currentOption = currentPackage.getOptions().stream()
                .filter(o -> o.getDurationMonths().equals(currentDuration))
                .findFirst()
                .orElseThrow(() -> new ServiceException("error.duration_config_not_found",
                        "INTERNAL_ERROR", org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        BigDecimal currentEffectivePrice = currentOption.getPriceStandard();
        if (currentOption.getPriceDiscounted() != null) {
            currentEffectivePrice = currentOption.getPriceDiscounted();
        }

        SubscriptionDetailsDto currentDetails = SubscriptionDetailsDto.builder()
                .subscriptionId(currentSub.getSubscriptionId())
                .packageId(currentPackage.getId().toString())
                .packageName(currentPackage.getName())
                .durationMonths(currentDuration)
                .effectivePrice(currentEffectivePrice)
                .currency(currentPackage.getCurrency())
                .remainingLimit(currentSub.getRemainingLimit())
                .build();

        List<UpgradeOptionDto> options = new ArrayList<>();
        List<SubscriptionPackage> allPackages = packageRepository.findByIsActiveTrue();

        for (SubscriptionPackage pkg : allPackages) {
            for (PackageOption option : pkg.getOptions()) {
                boolean isSamePackage = pkg.getId().equals(currentPackage.getId());
                boolean isDurationUpgrade = isSamePackage && option.getDurationMonths() > currentDuration;

                BigDecimal targetEffectivePrice = option.getPriceDiscounted() != null
                        ? option.getPriceDiscounted() : option.getPriceStandard();
                if (targetEffectivePrice == null) continue;

                int currentTier = getTierRank(currentPackage.getName());
                int targetTier = getTierRank(pkg.getName());
                boolean isTierUpgrade = !isSamePackage && targetTier > currentTier;

                if (targetDurationMonths != null && !option.getDurationMonths().equals(targetDurationMonths)) {
                    continue;
                }

                if (isDurationUpgrade || isTierUpgrade) {
                    BigDecimal payableDiff = targetEffectivePrice.subtract(currentEffectivePrice);
                    if (payableDiff.compareTo(BigDecimal.ZERO) <= 0) continue;

                    int targetTotal = option.getEntryLimit() != null ? option.getEntryLimit() : (pkg.getEntryLimit() != null ? pkg.getEntryLimit() : 0);
                    int currentRemaining = currentSub.getRemainingLimit() != null ? currentSub.getRemainingLimit() : 0;
                    int newRemaining = Math.max(0, targetTotal - currentRemaining);

                    String badge = null;
                    if (option.getPriceDiscounted() != null && option.getPriceStandard() != null
                            && option.getPriceDiscounted().compareTo(option.getPriceStandard()) < 0) {
                        badge = "discount";
                    }

                    options.add(UpgradeOptionDto.builder()
                            .type(isDurationUpgrade ? "duration_upgrade" : "tier_upgrade")
                            .target(TargetPackageDto.builder()
                                    .packageId(pkg.getId().toString())
                                    .optionId(option.getId())
                                    .packageName(pkg.getName())
                                    .durationMonths(option.getDurationMonths())
                                    .targetTotalLimit(targetTotal)
                                    .targetEffectivePrice(targetEffectivePrice)
                                    .build())
                            .payableDifference(payableDiff)
                            .newRemainingLimit(newRemaining)
                            .badge(badge)
                            .build());
                }
            }
        }

        return UpgradeOptionsResponse.builder()
                .current(currentDetails)
                .options(options)
                .build();
    }

    @Transactional
    public UpgradeCheckoutResponse checkout(Long userId, UpgradeCheckoutRequest request) {
        Subscription currentSub = subscriptionRepository.findById(request.currentSubscriptionId())
                .orElseThrow(() -> new ServiceException("error.no_active_subscription",
                        "NO_ACTIVE_SUBSCRIPTION", org.springframework.http.HttpStatus.CONFLICT));

        if (!currentSub.getUserId().equals(userId)) {
            throw new ServiceException("error.upgrade_unauthorized",
                    "UNAUTHORIZED", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        if (!"ACTIVE".equals(currentSub.getStatus())) {
            throw new ServiceException("error.no_active_subscription",
                    "NO_ACTIVE_SUBSCRIPTION", org.springframework.http.HttpStatus.CONFLICT);
        }

        Long targetPackageId = Long.parseLong(request.targetPackageId());
        SubscriptionPackage targetPackage = packageRepository.findById(targetPackageId)
                .orElseThrow(() -> new ServiceException("error.target_plan_not_found",
                        "PACKAGE_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND));

        if (!targetPackage.getIsActive()) {
            throw new ServiceException("error.target_plan_inactive",
                    "PACKAGE_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND);
        }

        PackageOption targetOption = targetPackage.getOptions().stream()
                .filter(o -> o.getId().equals(request.targetOptionId()))
                .findFirst()
                .orElseThrow(() -> new ServiceException("error.invalid_upgrade_request",
                        "INVALID_UPGRADE_REQUEST", org.springframework.http.HttpStatus.BAD_REQUEST));

        SubscriptionPackage currentPackage = packageRepository.findById(currentSub.getPackageId())
                .orElseThrow(() -> new ServiceException("error.plan_not_found",
                        "INTERNAL_ERROR", org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        long currentDurationLong = 1;
        if (currentSub.getEndAt() != null) {
            currentDurationLong = java.time.temporal.ChronoUnit.MONTHS.between(currentSub.getStartAt(), currentSub.getEndAt());
            if (currentDurationLong == 0) currentDurationLong = 1;
        }
        int currentDuration = (int) currentDurationLong;

        PackageOption currentOption = currentPackage.getOptions().stream()
                .filter(o -> o.getDurationMonths().equals(currentDuration))
                .findFirst()
                .orElseThrow(() -> new ServiceException("error.duration_config_not_found",
                        "INTERNAL_ERROR", org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        BigDecimal currentEffectivePrice = currentOption.getPriceDiscounted() != null
                ? currentOption.getPriceDiscounted() : currentOption.getPriceStandard();
        BigDecimal targetEffectivePrice = targetOption.getPriceDiscounted() != null
                ? targetOption.getPriceDiscounted() : targetOption.getPriceStandard();

        BigDecimal payableDiff = targetEffectivePrice.subtract(currentEffectivePrice);

        if (payableDiff.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("error.upgrade_not_eligible",
                    "UPGRADE_NOT_ELIGIBLE", org.springframework.http.HttpStatus.CONFLICT);
        }

        String orderId = "ord_" + UUID.randomUUID().toString().substring(0, 8);

        PaymentResultDto paymentResult = paymentService.processPayment(
                request.paymentMethodId(), payableDiff, targetPackage.getCurrency());

        Order order = Order.builder()
                .orderId(orderId)
                .userId(userId)
                .type("subscription_upgrade")
                .status(paymentResult.status())
                .amount(payableDiff)
                .currency(targetPackage.getCurrency())
                .createdAt(LocalDateTime.now())
                .providerReference(paymentResult.providerReference())
                .build();
        orderRepository.save(order);

        SubscriptionDetailsDto subDetails = null;

        if ("success".equals(paymentResult.status())) {
            int targetTotal = targetOption.getEntryLimit() != null ? targetOption.getEntryLimit() : (targetPackage.getEntryLimit() != null ? targetPackage.getEntryLimit() : 0);
            int currentRemaining = currentSub.getRemainingLimit() != null ? currentSub.getRemainingLimit() : 0;
            int newRemaining = Math.max(0, targetTotal - currentRemaining);

            currentSub.setPackageId(targetPackageId);
            currentSub.setTotalLimit(targetTotal);
            currentSub.setRemainingLimit(newRemaining);
            currentSub.setEndAt(currentSub.getStartAt().plusMonths(targetOption.getDurationMonths()));
            currentSub.setIsUpgraded(true);
            subscriptionRepository.save(currentSub);

            subDetails = SubscriptionDetailsDto.builder()
                    .subscriptionId(currentSub.getSubscriptionId())
                    .packageId(targetPackage.getId().toString())
                    .packageName(targetPackage.getName())
                    .durationMonths(targetOption.getDurationMonths())
                    .totalLimit(targetTotal)
                    .remainingLimit(newRemaining)
                    .startAt(currentSub.getStartAt() != null ? currentSub.getStartAt().toLocalDate() : null)
                    .endAt(currentSub.getEndAt() != null ? currentSub.getEndAt().toLocalDate() : null)
                    .build();
        }

        return UpgradeCheckoutResponse.builder()
                .orderId(orderId)
                .payment(paymentResult)
                .subscription(subDetails)
                .subscriptionUnchanged(!"success".equals(paymentResult.status()))
                .build();
    }

    public Order getOrder(Long userId, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ServiceException("error.order_not_found",
                        "ORDER_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ServiceException("error.order_not_found",
                    "ORDER_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return order;
    }

    public OrderResponse getOrderResponse(Long userId, String orderId) {
        Order order = getOrder(userId, orderId);
        OrderResponse response = new OrderResponse();
        response.setOrderId(order.getOrderId());
        response.setType(order.getType());
        response.setStatus(order.getStatus());
        response.setAmount(order.getAmount());
        response.setCurrency(order.getCurrency());
        if (order.getCreatedAt() != null) {
            response.setCreatedAt(order.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        return response;
    }

    private int getTierRank(String planName) {
        if (planName == null) return 0;
        String lower = planName.toLowerCase();
        if (lower.contains("platinum")) return 4;
        if (lower.contains("gold")) return 3;
        if (lower.contains("silver")) return 2;
        if (lower.contains("bronze")) return 1;
        return 0;
    }
}
