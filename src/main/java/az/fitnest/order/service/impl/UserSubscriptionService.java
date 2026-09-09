package az.fitnest.order.service.impl;

import az.fitnest.order.dto.ActiveSubscriptionResponse;
import az.fitnest.order.dto.SubscriptionDetailsDto;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import az.fitnest.order.util.UserContext;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.service.TranslationService;
import az.fitnest.order.grpc.PaymentGrpcClient;
import az.fitnest.order.grpc.NotificationGrpcClient;
import java.util.Optional;
import java.util.Collections;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserSubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPackageRepository packageRepository;
    private final az.fitnest.order.repository.GymVisitRepository gymVisitRepository;
    private final az.fitnest.order.repository.OrderRepository orderRepository;
    private final SubscriptionEventPublisher subscriptionEventPublisher;
    private final TranslationService translationService;
    private final PaymentGrpcClient paymentGrpcClient;
    private final NotificationGrpcClient notificationGrpcClient;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional
    public boolean checkIn(Long userId, Long gymId, boolean consumeFrozen) {
        List<Subscription> activeSubs = subscriptionRepository.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "FINISHED"));
        if (activeSubs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_active_subscription");
        }
        Subscription subscription = activeSubs.stream()
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

        if (subscription.getEndAt() != null && subscription.getEndAt().isBefore(LocalDateTime.now())) {
            throw new az.fitnest.order.exception.BadRequestException("error.membership_expired");
        }

        if (consumeFrozen) {
            if (subscription.getFrozenSessions() == null || subscription.getFrozenSessions() <= 0) {
                throw new az.fitnest.order.exception.BadRequestException("error.no_frozen_sessions_available");
            }
            subscription.setFrozenSessions(subscription.getFrozenSessions() - 1);
            subscriptionRepository.save(subscription);
            subscriptionEventPublisher.publishSubscriptionEvent(userId, "CHECKIN_FROZEN", subscription.getSubscriptionId());
        } else {
            if ("FINISHED".equals(subscription.getStatus())) {
                throw new az.fitnest.order.exception.BadRequestException("error.no_remaining_visits");
            }
            if (subscription.getRemainingLimit() != null) {
                if (subscription.getRemainingLimit() <= 0) {
                    subscription.setStatus("FINISHED");
                    subscriptionRepository.save(subscription);
                    throw new az.fitnest.order.exception.BadRequestException("error.no_remaining_visits");
                }
                subscription.setRemainingLimit(subscription.getRemainingLimit() - 1);
                if (subscription.getRemainingLimit() == 0 && (subscription.getFrozenSessions() == null || subscription.getFrozenSessions() == 0)) {
                    subscription.setStatus("FINISHED");
                }
                subscriptionRepository.save(subscription);
                subscriptionEventPublisher.publishSubscriptionEvent(userId, "CHECKIN", subscription.getSubscriptionId());
            }
        }

        az.fitnest.order.model.entity.GymVisit visit = az.fitnest.order.model.entity.GymVisit.builder()
                .userId(userId)
                .gymId(gymId)
                .subscriptionId(subscription.getSubscriptionId())
                .checkedInAt(LocalDateTime.now())
                .build();
        gymVisitRepository.save(visit);

        return true;
    }

    @Transactional(readOnly = true)
    public ActiveSubscriptionResponse getActiveSubscription(Long userId) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserSubscriptionService.class);
        try {
            String lang = UserContext.getCurrentLanguage();
            log.info("Fetching latest subscription for userId={}, lang={}", userId, lang);
            Subscription subscription = null;
            String subscriptionStatus = null;
            List<Subscription> allSubs = subscriptionRepository.findAllByUserIdOrderByStartAtDesc(userId);
            if (!allSubs.isEmpty()) {
                Subscription latest = allSubs.get(0);
                if (!"CANCELLED".equals(latest.getStatus()) && !"EXPIRED".equals(latest.getStatus())) {
                    subscription = latest;
                    String rawStatus = subscription.getStatus();
                    if (Boolean.TRUE.equals(subscription.getIsUpgraded())) {
                        subscriptionStatus = "changed";
                    } else if ("ACTIVE".equalsIgnoreCase(rawStatus) && subscription.getEndAt() != null &&
                            !subscription.getEndAt().isBefore(LocalDateTime.now()) &&
                            !subscription.getEndAt().isAfter(LocalDateTime.now().plusDays(7))) {
                        subscriptionStatus = "last_7_days";
                    } else {
                        subscriptionStatus = translationService.getTranslatedValue("SUBSCRIPTION_STATUS", rawStatus, "name", lang);
                        if (subscriptionStatus == null || subscriptionStatus.isEmpty()) {
                            subscriptionStatus = rawStatus != null ? rawStatus.toLowerCase() : "unknown";
                            if (subscriptionStatus.length() > 0) {
                                subscriptionStatus = subscriptionStatus.substring(0, 1).toUpperCase() + subscriptionStatus.substring(1);
                            }
                        }
                    }
                    log.info("Found latest subscription for userId={}, subscriptionId={}, status={}", userId, subscription.getSubscriptionId(), subscriptionStatus);
                }
            }
            if (subscription == null) {
                log.info("No subscription found for userId={}, returning No Plan", userId);
                String noPlanLabel = translationService.getTranslatedValue("SUBSCRIPTION_STATUS", "NONE", "name", lang);
                SubscriptionDetailsDto noPlanDetails = SubscriptionDetailsDto.builder()
                        .packageName(noPlanLabel != null ? noPlanLabel : "No Plan")
                        .frozenDaysUsed(0)
                        .allowedFreezeDays(0)
                        .remainingFreezeDays(0)
                        .automaticPaymentEnabled(false)
                        .build();
                return ActiveSubscriptionResponse.builder()
                        .status("None")
                        .subscription(noPlanDetails)
                        .build();
            }
            SubscriptionPackage pkg = packageRepository.findFullById(subscription.getPackageId())
                    .orElse(null);
            if (pkg == null) {
                log.warn("Package not found for packageId={} (userId={}), returning fallback details with status={}", subscription.getPackageId(), userId, subscriptionStatus);
                SubscriptionDetailsDto fallbackDetails = SubscriptionDetailsDto.builder()
                        .subscriptionId(subscription.getSubscriptionId())
                        .packageId(String.valueOf(subscription.getPackageId()))
                        .packageName("Bilinməyən Paket")
                        .durationMonths(1)
                        .durationLabel("1 ay")
                        .effectivePrice(java.math.BigDecimal.ZERO)
                        .currency("AZN")
                        .totalLimit(subscription.getTotalLimit())
                        .remainingLimit(subscription.getRemainingLimit())
                        .startAt(subscription.getStartAt() != null ? subscription.getStartAt().toLocalDate() : null)
                        .endAt(subscription.getEndAt() != null ? subscription.getEndAt().toLocalDate() : null)
                        .frozenDaysUsed(0)
                        .allowedFreezeDays(0)
                        .remainingFreezeDays(0)
                        .automaticPaymentEnabled(false)
                        .build();
                return ActiveSubscriptionResponse.builder()
                        .status(subscriptionStatus)
                        .subscription(fallbackDetails)
                        .build();
            }
            long durationMonths = 1;
            if (subscription.getEndAt() != null && subscription.getStartAt() != null) {
                durationMonths = java.time.temporal.ChronoUnit.MONTHS.between(subscription.getStartAt(), subscription.getEndAt());
                if (durationMonths == 0) durationMonths = 1;
            }
            Integer duration = (int) durationMonths;
            java.math.BigDecimal effectivePrice = java.math.BigDecimal.ZERO;
            PackageOption matchedOption = null;
            final Long assignedOptionId = subscription.getOptionId();
            if (pkg.getOptions() != null && assignedOptionId != null) {
                matchedOption = pkg.getOptions().stream()
                        .filter(o -> o.getId().equals(assignedOptionId))
                        .findFirst()
                        .orElse(null);
            }
            if (matchedOption == null && pkg.getOptions() != null) {
                matchedOption = pkg.getOptions().stream()
                        .filter(o -> o.getDurationMonths().equals(duration))
                        .findFirst()
                        .orElse(null);
            }
            if (matchedOption != null) {
                effectivePrice = matchedOption.getPriceDiscounted() != null
                        ? matchedOption.getPriceDiscounted()
                        : matchedOption.getPriceStandard();
            }
            Integer allowedFreezeDays = 0;
            Integer frozenDaysUsed = subscription.getFrozenDaysUsed() != null ? subscription.getFrozenDaysUsed() : 0;
            Integer remainingFreezeDays = allowedFreezeDays - frozenDaysUsed;
            Long optionId = matchedOption != null ? matchedOption.getId() : -1L;
            String localizedPackageName = translationService.getTranslatedValue("SUBSCRIPTIONPACKAGE", pkg.getId().toString(), "name", lang);
            if (localizedPackageName == null || localizedPackageName.isEmpty()) localizedPackageName = pkg.getName();

            java.util.List<az.fitnest.order.dto.PackageBenefitDto> benefitDtos = java.util.Collections.emptyList();
            if (pkg.getBenefits() != null && !pkg.getBenefits().isEmpty()) {
                benefitDtos = pkg.getBenefits().stream()
                        .map(b -> {
                            String ebId = pkg.getId() + "_" + b.getDescription();
                            String localizedBenefit = translationService.getTranslatedValue("PLANBENEFIT", ebId, "description", lang);
                            return az.fitnest.order.dto.PackageBenefitDto.builder()
                                    .description(localizedBenefit != null ? localizedBenefit : b.getDescription())
                                    .build();
                        })
                        .toList();
            }

            String durationLabel = translationService.getTranslatedValue("DURATION", duration.toString(), "label", lang);
            if (durationLabel == null || durationLabel.isEmpty()) durationLabel = duration + " ay";

            SubscriptionDetailsDto details = SubscriptionDetailsDto.builder()
                    .subscriptionId(subscription.getSubscriptionId())
                    .packageId(pkg.getId().toString())
                    .packageName(localizedPackageName)
                    .durationMonths(duration)
                    .durationLabel(durationLabel)
                    .effectivePrice(effectivePrice)
                    .currency(pkg.getCurrency())
                    .totalLimit(subscription.getTotalLimit())
                    .remainingLimit(subscription.getRemainingLimit())
                    .startAt(subscription.getStartAt() != null ? subscription.getStartAt().toLocalDate() : null)
                    .endAt(subscription.getEndAt() != null ? subscription.getEndAt().toLocalDate() : null)
                    .frozenAt(subscription.getFrozenAt() != null ? subscription.getFrozenAt().toLocalDate() : null)
                    .unfreezesAt(subscription.getUnfreezesAt() != null ? subscription.getUnfreezesAt().toLocalDate() : null)
                    .frozenDaysUsed(frozenDaysUsed)
                    .allowedFreezeDays(allowedFreezeDays)
                    .remainingFreezeDays(Math.max(0, remainingFreezeDays))
                    .optionId(optionId)
                    .benefits(benefitDtos)
                    .automaticPaymentEnabled(Boolean.TRUE.equals(subscription.getAutoPaymentEnabled()))
                    .build();
            log.info("Returning latest subscription details for userId={}, subscriptionId={}", userId, subscription.getSubscriptionId());
            return ActiveSubscriptionResponse.builder()
                    .status(subscriptionStatus)
                    .subscription(details)
                    .build();
        } catch (Exception ex) {
            log.error("Exception in getActiveSubscription for userId={}: {}", userId, ex.getMessage(), ex);
            ex.printStackTrace();
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public az.fitnest.order.dto.ActiveSubscriptionResponseV2 getActiveSubscriptionV2(Long userId) {
        ActiveSubscriptionResponse v1 = getActiveSubscription(userId);
        var coins = paymentGrpcClient.getCoinWallet(userId);
        return az.fitnest.order.dto.ActiveSubscriptionResponseV2.from(
                v1,
                coins.coinBalance(),
                coins.aznEquivalent(),
                coins.validityDate()
        );
    }

    @Transactional(readOnly = true)
    public az.fitnest.order.dto.ActiveSubscriptionResponseV3 getActiveSubscriptionV3(Long userId) {
        String lang = UserContext.getCurrentLanguage();
        List<Subscription> allSubs = subscriptionRepository.findAllByUserIdOrderByStartAtDesc(userId);
        Subscription subscription = allSubs.stream()
                .filter(item -> !"CANCELLED".equals(item.getStatus()) && !"EXPIRED".equals(item.getStatus()))
                .findFirst()
                .orElse(null);

        if (subscription == null) {
            return az.fitnest.order.dto.ActiveSubscriptionResponseV3.none();
        }

        SubscriptionPackage pkg = packageRepository.findFullById(subscription.getPackageId()).orElse(null);
        String subscriptionName = null;
        Integer durationMonths = null;

        if (pkg != null) {
            subscriptionName = translationService.getTranslatedValue(
                    "SUBSCRIPTIONPACKAGE", pkg.getId().toString(), "name", lang);
            if (subscriptionName == null || subscriptionName.isBlank()) {
                subscriptionName = pkg.getName();
            }

            PackageOption matchedOption = null;
            if (pkg.getOptions() != null && subscription.getOptionId() != null) {
                matchedOption = pkg.getOptions().stream()
                        .filter(option -> option.getId().equals(subscription.getOptionId()))
                        .findFirst()
                        .orElse(null);
            }
            if (matchedOption != null && matchedOption.getDurationMonths() != null) {
                durationMonths = matchedOption.getDurationMonths();
            }
        }

        if (durationMonths == null && subscription.getStartAt() != null && subscription.getEndAt() != null) {
            long months = java.time.temporal.ChronoUnit.MONTHS.between(
                    subscription.getStartAt(), subscription.getEndAt());
            durationMonths = months <= 0 ? 1 : (int) months;
        }

        String status = subscription.getStatus() == null
                ? "unknown"
                : subscription.getStatus().toLowerCase();

        return az.fitnest.order.dto.ActiveSubscriptionResponseV3.builder()
                .subscriptionName(subscriptionName)
                .planDurationMonths(durationMonths)
                .status(status)
                .nextPaymentDueAt(subscription.getEndAt() != null ? subscription.getEndAt().toLocalDate() : null)
                .build();
    }

    @Transactional
    public void freezeSubscription(Long userId) {
        List<Subscription> activeSubs = subscriptionRepository.findByUserIdAndStatus(userId, "ACTIVE");
        if (activeSubs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_active_subscription");
        }
        Subscription subscription = activeSubs.stream()
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

        if (subscription.getEndAt() != null && subscription.getEndAt().isBefore(LocalDateTime.now())) {
            throw new az.fitnest.order.exception.BadRequestException("error.membership_expired_cannot_freeze");
        }

        SubscriptionPackage pkg = packageRepository.findById(subscription.getPackageId())
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));

        long tempDurationMonths = 1;
        if (subscription.getEndAt() != null) {
            tempDurationMonths = java.time.temporal.ChronoUnit.MONTHS.between(subscription.getStartAt(), subscription.getEndAt());
            if (tempDurationMonths == 0) tempDurationMonths = 1;
        }
        final long durationMonths = tempDurationMonths;

        PackageOption matchedOption = pkg.getOptions().stream()
                .filter(o -> o.getDurationMonths().equals((int) durationMonths))
                .findFirst()
                .orElse(null);

        Integer allowedFreezeDays = 0;

        if (allowedFreezeDays == 0) {
            throw new az.fitnest.order.exception.BadRequestException("error.freeze_not_allowed_for_plan");
        }

        if (subscription.getFrozenDaysUsed() == null) {
            subscription.setFrozenDaysUsed(0);
        }

        int availableFreezeDays = allowedFreezeDays - subscription.getFrozenDaysUsed();

        if (availableFreezeDays <= 0) {
            throw new az.fitnest.order.exception.BadRequestException("error.freeze_days_exhausted");
        }

        int daysToFreeze = availableFreezeDays;

        LocalDateTime unfreezesAt = LocalDateTime.now().plusDays(daysToFreeze);

        if (subscription.getEndAt() != null) {
            subscription.setEndAt(subscription.getEndAt().plusDays(daysToFreeze));
        }

        subscription.setStatus("FROZEN");
        subscription.setFrozenAt(LocalDateTime.now());
        subscription.setUnfreezesAt(unfreezesAt);
        subscription.setFrozenDaysUsed(subscription.getFrozenDaysUsed() + daysToFreeze);
        subscription.setAllowedFreezeDays(allowedFreezeDays);

        subscriptionRepository.save(subscription);

        subscriptionEventPublisher.publishSubscriptionEvent(userId, "FREEZE", subscription.getSubscriptionId());
    }

    @Transactional
    public void unfreezeSubscription(Long userId) {
        List<Subscription> frozenSubs = subscriptionRepository.findByUserIdAndStatus(userId, "FROZEN");
        if (frozenSubs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_frozen_subscription");
        }
        Subscription subscription = frozenSubs.stream()
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

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime frozenAt = subscription.getFrozenAt();
        LocalDateTime unfreezesAt = subscription.getUnfreezesAt();

        if (frozenAt == null || unfreezesAt == null) {
            subscription.setStatus("ACTIVE");
            subscription.setFrozenAt(null);
            subscription.setUnfreezesAt(null);
            subscriptionRepository.save(subscription);
            return;
        }

        long hoursPassed = java.time.temporal.ChronoUnit.HOURS.between(frozenAt, now);
        int actualDaysUsed = (int) (hoursPassed / 24) + 1;

        long daysOriginallyFrozen = java.time.temporal.ChronoUnit.DAYS.between(frozenAt.toLocalDate(), unfreezesAt.toLocalDate());

        int daysToRefund = (int) daysOriginallyFrozen - actualDaysUsed;

        if (daysToRefund > 0) {
            if (subscription.getEndAt() != null) {
                subscription.setEndAt(subscription.getEndAt().minusDays(daysToRefund));
            }
            subscription.setFrozenDaysUsed(Math.max(0, subscription.getFrozenDaysUsed() - daysToRefund));
        }

        subscription.setStatus("ACTIVE");
        subscription.setFrozenAt(null);
        subscription.setUnfreezesAt(null);

        subscriptionRepository.save(subscription);

        subscriptionEventPublisher.publishSubscriptionEvent(userId, "UNFREEZE", subscription.getSubscriptionId());

        log.info("Manually unfroze subscription {} for user {}. Refunded {} days.",
                subscription.getSubscriptionId(), userId, Math.max(0, daysToRefund));
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void autoUnfreezeExpiredSubscriptions() {
        LocalDateTime now = LocalDateTime.now();
        List<Subscription> expiredFrozenSubs = subscriptionRepository.findExpiredFrozen(now);

        for (Subscription subscription : expiredFrozenSubs) {
            subscription.setStatus("ACTIVE");
            subscription.setFrozenAt(null);
            subscription.setUnfreezesAt(null);
            subscriptionRepository.save(subscription);

            log.info("Auto-unfroze subscription {} for user {}",
                    subscription.getSubscriptionId(), subscription.getUserId());
        }

        if (!expiredFrozenSubs.isEmpty()) {
            log.info("Auto-unfroze {} subscriptions", expiredFrozenSubs.size());
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void autoFinishExpiredSubscriptions() {
        LocalDateTime now = LocalDateTime.now();
        List<String> statuses = List.of("ACTIVE", "FROZEN");
        List<Subscription> expiredSubs = subscriptionRepository.findByStatusInAndEndAtBefore(statuses, now);
        for (Subscription subscription : expiredSubs) {
            subscription.setStatus("EXPIRED");
            subscriptionRepository.save(subscription);
            log.info("Auto-expired subscription {} for user {} (endAt={})", subscription.getSubscriptionId(), subscription.getUserId(), subscription.getEndAt());
        }
        if (!expiredSubs.isEmpty()) {
            log.info("Auto-expired {} subscriptions.", expiredSubs.size());
        }
    }

    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void autoRenewSubscriptions() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime tomorrow = now.plusDays(1);

        List<Subscription> eligibleSubs = subscriptionRepository.findByStatusAndAutoPaymentEnabledAndEndAtBetween(
                "ACTIVE", true, now, tomorrow);

        log.info("Checking {} subscriptions for auto-renewal", eligibleSubs.size());

        for (Subscription sub : eligibleSubs) {
            try {
                processAutoRenewal(sub);
            } catch (Exception e) {
                log.error("Failed to auto-renew subscription {} for user {}: {}",
                        sub.getSubscriptionId(), sub.getUserId(), e.getMessage());
                notificationGrpcClient.sendPushNotification(sub.getUserId(),
                        "Subscription Renewal Failed",
                        "We couldn't renew your subscription. Please check your payment method.");
            }
        }
    }

    private void processAutoRenewal(Subscription sub) {
        Long userId = sub.getUserId();
        List<az.fitnest.payment.grpc.UserCardDto> cards = paymentGrpcClient.getUserCards(userId);

        if (cards.isEmpty()) {
            throw new RuntimeException("No saved cards found for user");
        }

        String cardId = cards.get(0).getCardId();
        var paymentResult = paymentGrpcClient.payWithCard(userId, cardId, sub.getPackageId(), sub.getOptionId());

        if ("success".equalsIgnoreCase(paymentResult.getStatus())) {
            renewSubscription(sub);
            notificationGrpcClient.sendPushNotification(userId,
                    "Subscription Renewed",
                    "Your subscription has been automatically renewed successfully.");
            log.info("Successfully auto-renewed subscription {} for user {}", sub.getSubscriptionId(), userId);
        } else {
            throw new RuntimeException("Payment failed: " + paymentResult.getMessage());
        }
    }

    @Transactional
    public void disableAutoPayment(Long userId) {
        List<Subscription> activeSubs = subscriptionRepository.findByUserIdAndStatus(userId, "ACTIVE");
        if (activeSubs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_active_subscription");
        }
        Subscription sub = activeSubs.get(0);
        sub.setAutoPaymentEnabled(false);
        subscriptionRepository.save(sub);
        log.info("Disabled auto-payment for user {}, subscription ID: {}", userId, sub.getSubscriptionId());
    }

    @Transactional
    public void enableAutoPayment(Long userId) {
        List<Subscription> activeSubs = subscriptionRepository.findByUserIdAndStatus(userId, "ACTIVE");
        if (activeSubs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_active_subscription");
        }
        Subscription sub = activeSubs.get(0);

        PackageOption option = packageRepository.findById(sub.getPackageId())
                .flatMap(pkg -> pkg.getOptions().stream().filter(o -> o.getId().equals(sub.getOptionId())).findFirst())
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.duration_config_not_found"));

        if (option.getDurationMonths() != 1) {
            throw new az.fitnest.order.exception.BadRequestException("error.auto_payment_only_for_1_month");
        }

        sub.setAutoPaymentEnabled(true);
        subscriptionRepository.save(sub);
        log.info("Enabled auto-payment for user {}, subscription ID: {}", userId, sub.getSubscriptionId());
    }

    private void renewSubscription(Subscription current) {
        current.setStatus("FINISHED");
        subscriptionRepository.save(current);
        subscriptionEventPublisher.publishSubscriptionEvent(current.getUserId(), "FINISHED", current.getSubscriptionId());

        SubscriptionPackage pkg = packageRepository.findById(current.getPackageId())
                .orElseThrow(() -> new RuntimeException("Package not found"));
        PackageOption option = pkg.getOptions().stream()
                .filter(o -> o.getId().equals(current.getOptionId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Option not found"));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endAt = now.plusMonths(option.getDurationMonths());

        Subscription next = new Subscription();
        next.setUserId(current.getUserId());
        next.setPackageId(current.getPackageId());
        next.setOptionId(current.getOptionId());
        Integer renewLimit = option.getEntryLimit() != null ? option.getEntryLimit() : pkg.getEntryLimit();
        next.setStatus(renewLimit != null && renewLimit == 0 ? "FINISHED" : "ACTIVE");
        next.setStartAt(now);
        next.setEndAt(endAt);
        next.setTotalLimit(renewLimit);
        next.setRemainingLimit(renewLimit);
        next.setFrozenDaysUsed(0);
        next.setAllowedFreezeDays(0);
        next.setAutoPaymentEnabled(true);

        Subscription saved = subscriptionRepository.save(next);
        subscriptionEventPublisher.publishSubscriptionEvent(saved.getUserId(), "ASSIGNED", saved.getSubscriptionId());
    }

    @Transactional
    public az.fitnest.order.dto.AdminAssignSubscriptionResponse assignSubscriptionToUser(
            az.fitnest.order.dto.AdminAssignSubscriptionRequest request) {

        SubscriptionPackage pkg = packageRepository.findById(request.planId())
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));

        if (pkg.getIsActive() == null || !pkg.getIsActive()) {
            throw new az.fitnest.order.exception.BadRequestException("error.target_plan_inactive");
        }

        PackageOption option = pkg.getOptions().stream()
                .filter(o -> o.getId().equals(request.optionId()))
                .findFirst()
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.duration_config_not_found"));

        List<Subscription> toFinish = subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(request.userId(), "ACTIVE");
        toFinish.addAll(subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(request.userId(), "FINISHED"));
        toFinish.addAll(subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(request.userId(), "FROZEN"));
        toFinish.addAll(subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(request.userId(), "PENDING"));
        for (Subscription existing : toFinish) {
            if (!"CANCELLED".equals(existing.getStatus()) && !"EXPIRED".equals(existing.getStatus()) && !"FINISHED".equals(existing.getStatus())) {
                existing.setStatus("FINISHED");
                existing.setFrozenAt(null);
                existing.setUnfreezesAt(null);
                subscriptionRepository.save(existing);
                log.info("Set previous subscription {} for user {} to FINISHED", existing.getSubscriptionId(), request.userId());
                subscriptionEventPublisher.publishSubscriptionEvent(request.userId(), "FINISHED", existing.getSubscriptionId());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endAt = now.plusMonths(option.getDurationMonths());

        Integer entryLimit = option.getEntryLimit() != null ? option.getEntryLimit() : pkg.getEntryLimit();
        Integer freezeDays = 0;

        Subscription subscription = new Subscription();
        subscription.setUserId(request.userId());
        subscription.setPackageId(request.planId());
        subscription.setOptionId(option.getId());
        if (entryLimit != null && entryLimit == 0) {
            subscription.setStatus("FINISHED");
        } else {
            subscription.setStatus("ACTIVE");
        }
        subscription.setStartAt(now);
        subscription.setEndAt(endAt);
        subscription.setTotalLimit(entryLimit);
        subscription.setRemainingLimit(entryLimit);
        subscription.setFrozenDaysUsed(0);
        subscription.setAllowedFreezeDays(freezeDays);
        if (request.autoPaymentEnabled() != null && request.autoPaymentEnabled()) {
            if (option.getDurationMonths() != 1) {
                throw new az.fitnest.order.exception.BadRequestException("error.auto_payment_only_for_1_month");
            }
            subscription.setAutoPaymentEnabled(true);
        } else {
            subscription.setAutoPaymentEnabled(false);
        }

        Subscription saved = subscriptionRepository.save(subscription);
        log.info("Admin assigned plan {} option {} (duration={} months) to user {}, subscriptionId={}",
                pkg.getName(), option.getId(), option.getDurationMonths(), request.userId(), saved.getSubscriptionId());
        subscriptionEventPublisher.publishSubscriptionEvent(request.userId(), "ASSIGNED", saved.getSubscriptionId());

        return az.fitnest.order.dto.AdminAssignSubscriptionResponse.builder()
                .subscriptionId(saved.getSubscriptionId())
                .userId(saved.getUserId())
                .build();
    }

    @Transactional
    public void revokeSubscription(Long userId) {
        List<Subscription> activeSubs = subscriptionRepository.findByUserIdAndStatus(userId, "ACTIVE");
        if (activeSubs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_active_subscription");
        }
        Subscription subscription = activeSubs.stream()
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

        subscription.setStatus("CANCELLED");
        subscription.setFrozenAt(null);
        subscription.setUnfreezesAt(null);
        subscriptionRepository.save(subscription);
        log.info("Admin revoked subscription {} for user {}", subscription.getSubscriptionId(), userId);
        subscriptionEventPublisher.publishSubscriptionEvent(userId, "REVOKED", subscription.getSubscriptionId());
    }

    @Transactional
    public void removeAllSubscriptionsOfUser(Long userId) {
        List<Subscription> allSubs = subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(userId, "ACTIVE");
        allSubs.addAll(subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(userId, "FINISHED"));
        allSubs.addAll(subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(userId, "FROZEN"));
        allSubs.addAll(subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(userId, "PENDING"));
        for (Subscription sub : allSubs) {
            if (!"CANCELLED".equals(sub.getStatus()) && !"EXPIRED".equals(sub.getStatus())) {
                sub.setStatus("CANCELLED");
                sub.setFrozenAt(null);
                sub.setUnfreezesAt(null);
                subscriptionRepository.save(sub);
                subscriptionEventPublisher.publishSubscriptionEvent(userId, "CANCELLED", sub.getSubscriptionId());
            }
        }
    }

    public List<Long> getUserIdsByPackageId(Long packageId) {
        return subscriptionRepository.findByPackageId(packageId)
                .stream()
                .map(Subscription::getUserId)
                .toList();
    }

    public List<Long> getUserIdsByDurationMonths(int durationMonths) {
        return subscriptionRepository.findUserIdsByDurationMonths(durationMonths);
    }

    public List<Long> getUserIdsByType(String type) {
        LocalDateTime now = LocalDateTime.now();
        return switch (type.toLowerCase()) {
            case "all" -> subscriptionRepository.findAllUserIds();
            case "active" -> subscriptionRepository.findByStatusIn(List.of("ACTIVE", "FROZEN")).stream()
                    .map(Subscription::getUserId)
                    .distinct()
                    .toList();
            case "expired" -> subscriptionRepository.findByStatus("EXPIRED").stream()
                    .map(Subscription::getUserId)
                    .distinct()
                    .toList();
            case "upgraded" -> subscriptionRepository.findByIsUpgraded(true).stream()
                    .map(Subscription::getUserId)
                    .distinct()
                    .toList();
            case "last_7_days" -> subscriptionRepository.findByStatusInAndEndAtBetween(
                            List.of("ACTIVE", "FROZEN"), now, now.plusDays(7)).stream()
                    .map(Subscription::getUserId)
                    .distinct()
                    .toList();
            default -> List.of();
        };
    }

    public List<Long> getFilteredUserIds(az.fitnest.order.grpc.GetFilteredUserIdsRequest request) {
        String sortBy = request.getSortBy();
        boolean hasSort = sortBy != null && !sortBy.isEmpty();

        StringBuilder jpql = new StringBuilder();
        if (hasSort) {
            jpql.append("SELECT s.userId FROM Subscription s");
        } else {
            jpql.append("SELECT DISTINCT s.userId FROM Subscription s");
        }

        if (request.getDurationMonths() != 0) {
            jpql.append(" JOIN PackageOption o ON s.optionId = o.id");
        }

        List<String> whereClauses = new java.util.ArrayList<>();
        java.util.Map<String, Object> parameters = new java.util.HashMap<>();

        if (request.getPackageId() != 0) {
            whereClauses.add("s.packageId = :packageId");
            parameters.put("packageId", request.getPackageId());
        }

        if (request.getDurationMonths() != 0) {
            whereClauses.add("o.durationMonths = :durationMonths");
            parameters.put("durationMonths", request.getDurationMonths());
        }

        String status = request.getSubscriptionStatus();
        if (status != null && !status.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            switch (status.toUpperCase()) {
                case "ACTIVE":
                    whereClauses.add("s.status = 'ACTIVE'");
                    break;
                case "FROZEN":
                    whereClauses.add("s.status = 'FROZEN'");
                    break;
                case "FINISHED":
                    whereClauses.add("s.status IN ('FINISHED', 'EXPIRED', 'CANCELLED')");
                    break;
                case "LAST_7_DAYS":
                    whereClauses.add("s.status IN ('ACTIVE', 'FROZEN')");
                    whereClauses.add("s.endAt BETWEEN :now AND :endAtLimit");
                    parameters.put("now", now);
                    parameters.put("endAtLimit", now.plusDays(7));
                    break;
                case "CHANGED":
                    whereClauses.add("s.isUpgraded = true");
                    break;
            }
        } else {
            if ("FINISH_DATE_ASC".equalsIgnoreCase(sortBy) || "FINISH_DATE_DESC".equalsIgnoreCase(sortBy)) {
                whereClauses.add("s.status IN ('ACTIVE', 'FROZEN')");
            }
        }

        if (!whereClauses.isEmpty()) {
            jpql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        if (hasSort) {
            jpql.append(" GROUP BY s.userId");
            if ("FINISH_DATE_ASC".equalsIgnoreCase(sortBy)) {
                jpql.append(" ORDER BY MIN(s.endAt) ASC NULLS LAST");
            } else if ("FINISH_DATE_DESC".equalsIgnoreCase(sortBy)) {
                jpql.append(" ORDER BY MAX(s.endAt) DESC NULLS LAST");
            }
        }

        var query = entityManager.createQuery(jpql.toString(), Long.class);
        for (var entry : parameters.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }

        return query.getResultList();
    }

    public az.fitnest.order.grpc.SubscriptionStatisticsResponse getSubscriptionStatistics() {
        LocalDateTime now = LocalDateTime.now();
        long activeOrFrozen = subscriptionRepository.countByStatusIn(List.of("ACTIVE", "FROZEN"));
        long finished = subscriptionRepository.countByStatus("FINISHED");
        long last7Days = subscriptionRepository.countByStatusInAndEndAtBetween(List.of("ACTIVE", "FROZEN"), now, now.plusDays(7));

        return az.fitnest.order.grpc.SubscriptionStatisticsResponse.newBuilder()
                .setUsersActiveOrFrozen(activeOrFrozen)
                .setUsersFinished(finished)
                .setUsersLast7Days(last7Days)
                .build();
    }

    @Transactional(readOnly = true)
    public az.fitnest.order.dto.AdminUserSubscriptionResponse getUserSubscriptionDetail(Long userId) {
        log.info("Fetching user subscription detail for admin. User ID: {}", userId);
        
        List<Subscription> allSubs = subscriptionRepository.findAllByUserIdOrderByStartAtDesc(userId);
        if (allSubs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_subscription_found");
        }
        
        Subscription sub = allSubs.get(0);
        if ("CANCELLED".equals(sub.getStatus()) || "EXPIRED".equals(sub.getStatus())) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_subscription_found");
        }
        SubscriptionPackage pkg = packageRepository.findById(sub.getPackageId())
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        
        PackageOption option = pkg.getOptions().stream()
                .filter(o -> o.getId().equals(sub.getOptionId()))
                .findFirst()
                .orElse(null);

        return az.fitnest.order.dto.AdminUserSubscriptionResponse.builder()
                .packageId(sub.getPackageId())
                .packageName(pkg.getName())
                .optionId(sub.getOptionId())
                .optionDuration(option != null ? option.getDurationMonths() : null)
                .price(option != null ? option.getPriceStandard() : pkg.getPrice())
                .discountedPrice(option != null ? option.getPriceDiscounted() : null)
                .startDate(sub.getStartAt())
                .endDate(sub.getEndAt())
                .totalEntryLimit(sub.getTotalLimit())
                .userRemainingLimit(sub.getRemainingLimit())
                .build();
    }

    @Transactional
    public az.fitnest.order.dto.AdminUserSubscriptionResponse updateEntryLimit(
            Long userId, az.fitnest.order.dto.UpdateEntryLimitRequest request) {
        log.info("Admin updating entry limit for userId={}, remainingLimit={}, totalLimit={}",
                userId, request.remainingLimit(), request.totalLimit());

        List<Subscription> allSubs = subscriptionRepository.findAllByUserIdOrderByStartAtDesc(userId);
        if (allSubs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_subscription_found");
        }

        Subscription sub = allSubs.get(0);
        if ("CANCELLED".equals(sub.getStatus()) || "EXPIRED".equals(sub.getStatus())) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_subscription_found");
        }

        int newRemaining = request.remainingLimit();

        Integer newTotal;
        if (request.totalLimit() != null) {
            newTotal = Math.max(request.totalLimit(), newRemaining);
        } else {
            int currentTotal = sub.getTotalLimit() != null ? sub.getTotalLimit() : 0;
            newTotal = Math.max(currentTotal, newRemaining);
        }

        sub.setRemainingLimit(newRemaining);
        sub.setTotalLimit(newTotal);

        boolean expired = sub.getEndAt() != null && sub.getEndAt().isBefore(LocalDateTime.now());
        boolean hasFrozen = sub.getFrozenSessions() != null && sub.getFrozenSessions() > 0;
        if (newRemaining <= 0 && !hasFrozen) {
            if ("ACTIVE".equals(sub.getStatus())) {
                sub.setStatus("FINISHED");
            }
        } else if (newRemaining > 0 && !expired && "FINISHED".equals(sub.getStatus())) {
            sub.setStatus("ACTIVE");
        }

        subscriptionRepository.save(sub);
        subscriptionEventPublisher.publishSubscriptionEvent(userId, "LIMIT_UPDATED", sub.getSubscriptionId());
        log.info("Admin updated entry limit for userId={}, subscriptionId={}, remaining={}, total={}, status={}",
                userId, sub.getSubscriptionId(), newRemaining, newTotal, sub.getStatus());

        return getUserSubscriptionDetail(userId);
    }

    @Transactional
    public void freezeSession(Long userId) {
        List<Subscription> activeSubs = subscriptionRepository.findByUserIdAndStatus(userId, "ACTIVE");
        if (activeSubs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_active_subscription");
        }
        Subscription subscription = activeSubs.stream()
                .max((a, b) -> a.getEndAt().compareTo(b.getEndAt()))
                .get();

        if (subscription.getRemainingLimit() == null || subscription.getRemainingLimit() <= 0) {
            throw new az.fitnest.order.exception.BadRequestException("error.no_remaining_visits");
        }

        subscription.setRemainingLimit(subscription.getRemainingLimit() - 1);
        if (subscription.getFrozenSessions() == null) {
            subscription.setFrozenSessions(0);
        }
        subscription.setFrozenSessions(subscription.getFrozenSessions() + 1);

        if (subscription.getRemainingLimit() == 0) {
            subscription.setStatus("FINISHED");
        }

        subscriptionRepository.save(subscription);
        log.info("Froze 1 session for user {}. Remaining: {}, Frozen: {}", userId, subscription.getRemainingLimit(), subscription.getFrozenSessions());
    }

    @Transactional
    public void restoreSession(Long userId) {
        List<Subscription> subs = subscriptionRepository.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "FINISHED"));
        if (subs.isEmpty()) {
            return;
        }
        Subscription subscription = subs.stream()
                .max((a, b) -> a.getEndAt().compareTo(b.getEndAt()))
                .get();

        if (subscription.getFrozenSessions() == null || subscription.getFrozenSessions() <= 0) {
            log.warn("Attempted to restore session for user {} but no sessions are frozen.", userId);
            return;
        }

        subscription.setFrozenSessions(subscription.getFrozenSessions() - 1);
        if (subscription.getRemainingLimit() == null) {
            subscription.setRemainingLimit(0);
        }
        subscription.setRemainingLimit(subscription.getRemainingLimit() + 1);
        
        if ("FINISHED".equals(subscription.getStatus()) && subscription.getRemainingLimit() > 0) {
            subscription.setStatus("ACTIVE");
        }

        subscriptionRepository.save(subscription);
        log.info("Restored 1 session for user {}. Remaining: {}, Frozen: {}", userId, subscription.getRemainingLimit(), subscription.getFrozenSessions());
    }

    @Transactional
    public void consumeFrozenSession(Long userId) {
        List<Subscription> subs = subscriptionRepository.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "FINISHED"));
        if (subs.isEmpty()) {
            throw new az.fitnest.order.exception.ResourceNotFoundException("error.no_active_subscription");
        }
        Subscription subscription = subs.stream()
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

        if (subscription.getFrozenSessions() == null || subscription.getFrozenSessions() <= 0) {
            log.warn("Attempted to consume frozen session for user {} but none are frozen.", userId);
            return;
        }

        subscription.setFrozenSessions(subscription.getFrozenSessions() - 1);
        subscriptionRepository.save(subscription);
        log.info("Consumed/Deleted 1 frozen session for user {}. Remaining frozen: {}", userId, subscription.getFrozenSessions());
    }
}
