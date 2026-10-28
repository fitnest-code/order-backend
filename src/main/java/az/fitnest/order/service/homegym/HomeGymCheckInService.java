package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.dto.homegym.HomeGymCheckInContext;
import az.fitnest.order.dto.homegym.HomeGymCheckInResult;
import az.fitnest.order.event.SubscriptionEvent;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.HomeGymConfirmationToken;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.enums.CheckInDecision;
import az.fitnest.order.model.enums.ConfirmationType;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.HomeGymEndReason;
import az.fitnest.order.model.enums.HomeGymStatus;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.HomeGymConfirmationTokenRepository;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * QR check-in decision engine (spec R3, R5-R8, R11, R14-R18).
 *
 * <p>Catalog resolves the time-slot rule and price snapshot before calling order. This
 * service performs the stateful part: home-gym vs standard entry, the R5 switch warning,
 * counter maintenance and the atomic HOME_GYM -> STANDARD transition.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HomeGymCheckInService {

    public static final String CONFIRMATION_SWITCH_TO_STANDARD = "SWITCH_TO_STANDARD";
    private static final int CONFIRMATION_TTL_MINUTES = 5;
    /** R6: other gyms are locked once the home gym has 12 visits in the subscription month. */
    private static final int OTHER_GYMS_LOCK_THRESHOLD = HomeGymPeriodCalculator.DEFAULT_STANDARD_LIMIT;

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionHomeGymRepository homeGymRepository;
    private final HomeGymConfirmationTokenRepository confirmationTokenRepository;
    private final GymVisitRepository gymVisitRepository;
    private final CatalogServiceGrpcClient catalogClient;
    private final HomeGymPeriodCalculator periodCalculator;
    private final SubscriptionEventPublisher eventPublisher;

    @Transactional
    public HomeGymCheckInResult checkIn(HomeGymCheckInContext context) {
        Long userId = context.userId();
        Long gymId = context.gymId();
        LocalDateTime now = periodCalculator.now();
        LocalDateTime dayStart = periodCalculator.dayStart(now);
        LocalDateTime dayEnd = periodCalculator.nextDayStart(now);

        Subscription locked = lockCurrentSubscription(userId);
        if (locked == null) {
            return rejected(CheckInDecision.NO_ACTIVE_SUBSCRIPTION, "home_gym.no_active_subscription");
        }
        if ("FROZEN".equals(locked.getStatus())) {
            return rejected(CheckInDecision.SUBSCRIPTION_FROZEN, "error.subscription_frozen");
        }
        if (locked.getEndAt() != null && locked.getEndAt().isBefore(now)) {
            return rejected(CheckInDecision.NO_ACTIVE_SUBSCRIPTION, "home_gym.no_active_subscription");
        }

        Subscription subscription = locked;
        LocalDate periodStart = periodCalculator.periodStart(subscription.getStartAt(), now.toLocalDate());
        int limit = resolveLimit(subscription);

        SubscriptionHomeGym active = homeGymRepository.findActiveForUpdate(subscription.getSubscriptionId())
                .orElse(null);
        EntryMode mode = subscription.getEntryMode() != null
                ? subscription.getEntryMode() : EntryMode.HOME_GYM_PENDING;
        if (active != null && mode != EntryMode.HOME_GYM) {
            // Repair a partially applied transition (e.g. crash between row and mode writes).
            mode = EntryMode.HOME_GYM;
            subscription.setEntryMode(mode);
        }

        long homeVisits = active == null ? 0
                : gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                        subscription.getSubscriptionId(), periodStart, VisitEntryType.HOME_UNLIMITED, active.getGymId());
        long standardVisits = gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                subscription.getSubscriptionId(), periodStart, VisitEntryType.STANDARD);
        // Once the subscription is in STANDARD mode every HOME_UNLIMITED visit of the month
        // counts against the limit too (R5). The same total is used for the limit check even
        // in HOME_GYM mode so home visits made before an R13/R11 home-gym change are not lost.
        long allHomeVisits = gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                subscription.getSubscriptionId(), periodStart, VisitEntryType.HOME_UNLIMITED);
        long standardVisitsUsed = standardVisits + (mode == EntryMode.STANDARD ? allHomeVisits : 0);
        long totalVisits = standardVisits + allHomeVisits;

        boolean atHomeGym = active != null && active.getGymId().equals(gymId);

        // R10: reservations (consumeFrozen) keep their legacy behaviour and never consume
        // the monthly limit, but the home-gym decision still applies for entry type.
        if (context.consumeFrozen()) {
            // Preserve the legacy reservation contract: a frozen session must exist.
            if (subscription.getFrozenSessions() == null || subscription.getFrozenSessions() <= 0) {
                throw new az.fitnest.order.exception.BadRequestException("error.no_frozen_sessions_available");
            }
            if (atHomeGym) {
                GymVisit visit = recordVisit(subscription, gymId, active.getGymId(), periodStart, now,
                        VisitEntryType.HOME_UNLIMITED, context, false);
                consumeFrozenSession(subscription);
                publishCheckIn(subscription, true);
                return entered(CheckInDecision.ENTERED_HOME_UNLIMITED, VisitEntryType.HOME_UNLIMITED, visit,
                        active.getGymId(), standardVisitsUsed, limit, homeVisits, mode);
            }
            if (active == null && mode == EntryMode.HOME_GYM_PENDING) {
                GymVisit visit = recordVisit(subscription, gymId, null, periodStart, now,
                        VisitEntryType.STANDARD, context, false);
                consumeFrozenSession(subscription);
                publishCheckIn(subscription, true);
                return entered(CheckInDecision.ENTERED_OFFER_HOME_GYM, VisitEntryType.STANDARD, visit,
                        null, standardVisitsUsed, limit, homeVisits, mode);
            }
            GymVisit visit = recordVisit(subscription, gymId, active != null ? active.getGymId() : null,
                    periodStart, now, VisitEntryType.STANDARD, context, false);
            consumeFrozenSession(subscription);
            publishCheckIn(subscription, true);
            return entered(CheckInDecision.ENTERED_STANDARD, VisitEntryType.STANDARD, visit,
                    active != null ? active.getGymId() : null, standardVisitsUsed, limit, homeVisits, mode);
        }

        // 1. Entering the active home gym: R21 daily and monthly gates, then unlimited entry (R3).
        if (atHomeGym) {
            long homeVisitsToday = gymVisitRepository.countCountableBySubscriptionAndGymAndTypeBetween(
                    subscription.getSubscriptionId(), gymId, VisitEntryType.HOME_UNLIMITED, dayStart, dayEnd);
            if (homeVisitsToday >= HomeGymPeriodCalculator.HOME_DAILY_VISIT_LIMIT) {
                return dailyLimitReached(subscription, active, homeVisits, standardVisitsUsed, limit,
                        periodStart, now);
            }
            if (homeVisits >= HomeGymPeriodCalculator.HOME_MONTHLY_VISIT_LIMIT) {
                return monthlyLimitReached(subscription, active, homeVisits, standardVisitsUsed, limit,
                        periodStart);
            }
            GymVisit visit = recordVisit(subscription, gymId, active.getGymId(), periodStart, now,
                    VisitEntryType.HOME_UNLIMITED, context, true);
            publishCheckIn(subscription, false);
            return entered(CheckInDecision.ENTERED_HOME_UNLIMITED, VisitEntryType.HOME_UNLIMITED, visit,
                    active.getGymId(), standardVisitsUsed, limit, homeVisits + 1, mode);
        }

        // 2. Another gym with an active home gym: R5/R6.
        if (active != null) {
            if (homeVisits >= OTHER_GYMS_LOCK_THRESHOLD) {
                return otherGymsLocked(subscription, gymId, active, homeVisits, standardVisitsUsed, limit, periodStart);
            }
            if (totalVisits >= limit) {
                return limitExceeded(subscription, gymId, active, limit, standardVisitsUsed, homeVisits,
                        periodStart, now);
            }
            String confirmation = context.confirmation();
            boolean confirmed = CONFIRMATION_SWITCH_TO_STANDARD.equalsIgnoreCase(confirmation);
            if (!confirmed) {
                return confirmationRequired(subscription, gymId, active, homeVisits, standardVisitsUsed, limit, now);
            }
            HomeGymConfirmationToken token = validateConfirmationToken(
                    context, subscription, active, homeVisits, now);
            if (token == null) {
                return confirmationRequired(subscription, gymId, active, homeVisits, standardVisitsUsed, limit, now);
            }
            if (totalVisits >= limit) {
                return limitExceeded(subscription, gymId, active, limit, standardVisitsUsed, homeVisits,
                        periodStart, now);
            }

            token.setUsedAt(now);
            confirmationTokenRepository.save(token);

            // R5: HOME_GYM -> STANDARD and the visit are written in the same transaction.
            GymVisit visit = recordVisit(subscription, gymId, active.getGymId(), periodStart, now,
                    VisitEntryType.STANDARD, context, true);
            String homeGymName = active.getGymId().equals(gymId) ? null
                    : safeGymName(active.getGymId(), subscription.getPackageId());
            active.setStatus(HomeGymStatus.REMOVED_BY_SWITCH);
            active.setEndedAt(now);
            active.setEndReason(HomeGymEndReason.SWITCH);
            active.setEndReasonVisitId(visit.getId());
            homeGymRepository.save(active);

            subscription.setEntryMode(EntryMode.STANDARD);
            decrementRemainingLimit(subscription, false);
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent(subscription.getUserId(),
                    "HOME_GYM_REMOVED_BY_SWITCH", subscription.getSubscriptionId());
            event.setGymId(active.getGymId());
            event.setPreviousGymId(active.getGymId());
            event.setGymName(homeGymName);
            eventPublisher.publishSubscriptionEvent(subscription.getUserId(), event.getAction(),
                    subscription.getSubscriptionId(), event);

            return entered(CheckInDecision.ENTERED_STANDARD, VisitEntryType.STANDARD, visit,
                    null, totalVisits + 1, limit, homeVisits, EntryMode.STANDARD);
        }

        // 3. Standard mode / no home gym yet.
        if (totalVisits >= limit) {
            return limitExceeded(subscription, gymId, null, limit, standardVisitsUsed, homeVisits, periodStart, now);
        }

        GymVisit visit = recordVisit(subscription, gymId, null, periodStart, now,
                VisitEntryType.STANDARD, context, true);
        decrementRemainingLimit(subscription, mode == EntryMode.STANDARD);
        subscriptionRepository.save(subscription);
        publishCheckIn(subscription, false);

        boolean offer = mode == EntryMode.HOME_GYM_PENDING
                && totalVisits == 0
                && !homeGymRepository.existsBySubscriptionIdAndStatus(subscription.getSubscriptionId(),
                HomeGymStatus.ACTIVE)
                && !homeGymRepository.existsBySubscriptionIdAndStatus(subscription.getSubscriptionId(),
                HomeGymStatus.ENDED)
                && !homeGymRepository.existsBySubscriptionIdAndStatus(subscription.getSubscriptionId(),
                HomeGymStatus.REMOVED_BY_SWITCH)
                && !homeGymRepository.existsBySubscriptionIdAndStatus(subscription.getSubscriptionId(),
                HomeGymStatus.CHANGED);

        return entered(offer ? CheckInDecision.ENTERED_OFFER_HOME_GYM : CheckInDecision.ENTERED_STANDARD,
                VisitEntryType.STANDARD, visit, null, standardVisitsUsed + 1, limit, homeVisits, mode);
    }

    // ------------------------------------------------------------------
    // Decisions
    // ------------------------------------------------------------------

    private HomeGymCheckInResult entered(CheckInDecision decision, VisitEntryType entryType, GymVisit visit,
                                          Long homeGymId, long standardVisits, int limit, long homeVisits,
                                          EntryMode mode) {
        return new HomeGymCheckInResult(
                true,
                decision,
                messageKey(decision),
                entryType,
                visit.getId(),
                homeGymId,
                null,
                standardVisits,
                limit,
                homeVisits,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private HomeGymCheckInResult rejected(CheckInDecision decision, String messageKey) {
        return new HomeGymCheckInResult(false, decision, messageKey, null, null, null, null,
                0, HomeGymPeriodCalculator.DEFAULT_STANDARD_LIMIT, 0, null, null, null, null, null, null, null);
    }

    private HomeGymCheckInResult otherGymsLocked(Subscription subscription, Long gymId,
                                                 SubscriptionHomeGym active, long homeVisits,
                                                 long standardVisits, int limit, LocalDate periodStart) {
        return new HomeGymCheckInResult(
                false,
                CheckInDecision.OTHER_GYMS_LOCKED,
                "home_gym.other_gyms_locked",
                null,
                null,
                active.getGymId(),
                gymId,
                standardVisits,
                limit,
                homeVisits,
                periodCalculator.toOffset(periodCalculator.periodEndsAt(subscription.getStartAt())),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private HomeGymCheckInResult limitExceeded(Subscription subscription, Long gymId,
                                               SubscriptionHomeGym active, int limit, long standardVisits,
                                               long homeVisits, LocalDate periodStart, LocalDateTime now) {
        return new HomeGymCheckInResult(
                false,
                CheckInDecision.VISIT_LIMIT_EXCEEDED,
                "qr.limit_reached",
                null,
                null,
                active != null ? active.getGymId() : null,
                gymId,
                standardVisits,
                limit,
                homeVisits,
                null,
                periodCalculator.toOffset(periodCalculator.periodEndsAt(subscription.getStartAt())),
                null,
                null,
                null,
                null,
                null);
    }

    private HomeGymCheckInResult confirmationRequired(Subscription subscription, Long gymId,
                                                      SubscriptionHomeGym active, long homeVisits,
                                                      long standardVisits, int limit, LocalDateTime now) {
        HomeGymConfirmationToken token = createToken(subscription, gymId, now);
        String homeGymName = safeGymName(active.getGymId(), subscription.getPackageId());
        return new HomeGymCheckInResult(
                false,
                CheckInDecision.CONFIRMATION_REQUIRED,
                "home_gym.switch_warning",
                null,
                null,
                active.getGymId(),
                gymId,
                standardVisits,
                limit,
                homeVisits,
                null,
                null,
                ConfirmationType.SWITCH_TO_STANDARD.name(),
                token.getToken(),
                periodCalculator.toOffset(token.getExpiresAt()),
                homeGymName,
                null);
    }

    /** R21: the home gym already had its one visit of the Baku calendar day. */
    private HomeGymCheckInResult dailyLimitReached(Subscription subscription, SubscriptionHomeGym active,
                                                   long homeVisits, long standardVisits, int limit,
                                                   LocalDate periodStart, LocalDateTime now) {
        return new HomeGymCheckInResult(
                false,
                CheckInDecision.DAILY_LIMIT_REACHED,
                "home_gym.daily_limit",
                null,
                null,
                active.getGymId(),
                active.getGymId(),
                standardVisits,
                limit,
                homeVisits,
                null,
                periodCalculator.toOffset(periodCalculator.periodEndsAt(
                        subscription.getStartAt(), periodStart)),
                null,
                null,
                null,
                null,
                periodCalculator.toOffset(periodCalculator.nextDayStart(now)));
    }

    /** R21: the home gym already had its 30 visits of the subscription month. */
    private HomeGymCheckInResult monthlyLimitReached(Subscription subscription, SubscriptionHomeGym active,
                                                     long homeVisits, long standardVisits, int limit,
                                                     LocalDate periodStart) {
        return new HomeGymCheckInResult(
                false,
                CheckInDecision.HOME_MONTHLY_LIMIT_REACHED,
                "home_gym.monthly_limit",
                null,
                null,
                active.getGymId(),
                active.getGymId(),
                standardVisits,
                limit,
                homeVisits,
                null,
                periodCalculator.toOffset(periodCalculator.periodEndsAt(
                        subscription.getStartAt(), periodStart)),
                null,
                null,
                null,
                null,
                null);
    }

    // ------------------------------------------------------------------
    // Token handling
    // ------------------------------------------------------------------

    private HomeGymConfirmationToken createToken(Subscription subscription, Long targetGymId, LocalDateTime now) {
        HomeGymConfirmationToken token = HomeGymConfirmationToken.builder()
                .token("c-" + UUID.randomUUID())
                .userId(subscription.getUserId())
                .subscriptionId(subscription.getSubscriptionId())
                .targetGymId(targetGymId)
                .confirmationType(ConfirmationType.SWITCH_TO_STANDARD)
                .createdAt(now)
                .expiresAt(now.plusMinutes(CONFIRMATION_TTL_MINUTES))
                .build();
        return confirmationTokenRepository.save(token);
    }

    /**
     * A token is valid only when the conditions it was issued for are unchanged:
     * same user/subscription/target gym, not expired, not used, and no home-gym visit
     * happened after the warning was shown (spec "Kənar hallar").
     */
    private HomeGymConfirmationToken validateConfirmationToken(HomeGymCheckInContext context,
                                                               Subscription subscription,
                                                               SubscriptionHomeGym active,
                                                               long homeVisits,
                                                               LocalDateTime now) {
        String raw = context.confirmationToken();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Optional<HomeGymConfirmationToken> found = confirmationTokenRepository.findByTokenAndUsedAtIsNull(raw);
        if (found.isEmpty()) {
            return null;
        }
        HomeGymConfirmationToken token = found.get();
        boolean matches = token.getUserId().equals(subscription.getUserId())
                && token.getSubscriptionId().equals(subscription.getSubscriptionId())
                && token.getTargetGymId().equals(context.gymId())
                && token.getConfirmationType() == ConfirmationType.SWITCH_TO_STANDARD;
        boolean usable = matches && !token.getExpiresAt().isBefore(now)
                && homeVisits < OTHER_GYMS_LOCK_THRESHOLD;
        if (usable) {
            Optional<GymVisit> latestHomeVisit = gymVisitRepository
                    .findFirstBySubscriptionIdAndGymIdAndEntryTypeOrderByCheckedInAtDesc(
                            subscription.getSubscriptionId(), active.getGymId(),
                            VisitEntryType.HOME_UNLIMITED);
            usable = latestHomeVisit.isEmpty()
                    || !latestHomeVisit.get().getCheckedInAt().isAfter(token.getCreatedAt());
        }
        if (!usable) {
            // Stale or invalid tokens are single-use as well: burn them and re-issue.
            token.setUsedAt(now);
            confirmationTokenRepository.save(token);
            return null;
        }
        return token;
    }

    // ------------------------------------------------------------------
    // Persistence helpers
    // ------------------------------------------------------------------

    private GymVisit recordVisit(Subscription subscription, Long gymId, Long homeGymId, LocalDate periodStart,
                                 LocalDateTime now, VisitEntryType entryType, HomeGymCheckInContext context,
                                 boolean countsTowardsLimit) {
        GymVisit visit = GymVisit.builder()
                .userId(subscription.getUserId())
                .gymId(gymId)
                .subscriptionId(subscription.getSubscriptionId())
                .checkedInAt(now)
                .entryType(entryType)
                .homeGymId(homeGymId)
                .periodStart(periodStart)
                .timeSlotRuleId(context.timeSlotRuleId())
                .pricePackage(context.pricePackage())
                .singlePrice(context.singlePrice())
                .unlimitedPrice(context.unlimitedPrice())
                .countsTowardsLimit(countsTowardsLimit)
                .build();
        return gymVisitRepository.save(visit);
    }

    private void decrementRemainingLimit(Subscription subscription, boolean finishWhenEmpty) {
        if (subscription.getRemainingLimit() == null) {
            return;
        }
        int remaining = Math.max(0, subscription.getRemainingLimit() - 1);
        subscription.setRemainingLimit(remaining);
        if (finishWhenEmpty && remaining == 0
                && (subscription.getFrozenSessions() == null || subscription.getFrozenSessions() == 0)
                && !"FINISHED".equals(subscription.getStatus())) {
            subscription.setStatus("FINISHED");
        }
    }

    private void consumeFrozenSession(Subscription subscription) {
        if (subscription.getFrozenSessions() != null && subscription.getFrozenSessions() > 0) {
            subscription.setFrozenSessions(subscription.getFrozenSessions() - 1);
            subscriptionRepository.save(subscription);
            eventPublisher.publishSubscriptionEvent(subscription.getUserId(), "CHECKIN_FROZEN",
                    subscription.getSubscriptionId());
        }
    }

    private void publishCheckIn(Subscription subscription, boolean frozen) {
        if (!frozen) {
            eventPublisher.publishSubscriptionEvent(subscription.getUserId(), "CHECKIN",
                    subscription.getSubscriptionId());
        }
    }

    private Subscription lockCurrentSubscription(Long userId) {
        List<Subscription> candidates = subscriptionRepository.findByUserIdAndStatusIn(userId,
                List.of("ACTIVE", "FINISHED"));
        if (candidates.isEmpty()) {
            return lockFirst(subscriptionRepository.findByUserIdAndStatus(userId, "FROZEN"));
        }
        Subscription current = candidates.stream()
                .max((a, b) -> compareByEndAt(a, b))
                .orElse(null);
        if (current == null) {
            return null;
        }
        return subscriptionRepository.findByIdForUpdate(current.getSubscriptionId()).orElse(current);
    }

    private Subscription lockFirst(List<Subscription> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return null;
        }
        Subscription current = subscriptions.get(0);
        return subscriptionRepository.findByIdForUpdate(current.getSubscriptionId()).orElse(current);
    }

    private int compareByEndAt(Subscription a, Subscription b) {
        if (a.getEndAt() != null && b.getEndAt() != null) {
            return a.getEndAt().compareTo(b.getEndAt());
        } else if (a.getEndAt() != null) {
            return 1;
        } else if (b.getEndAt() != null) {
            return -1;
        } else if (a.getStartAt() != null && b.getStartAt() != null) {
            return a.getStartAt().compareTo(b.getStartAt());
        }
        return 0;
    }

    private int resolveLimit(Subscription subscription) {
        if (subscription.getTotalLimit() != null && subscription.getTotalLimit() > 0) {
            return subscription.getTotalLimit();
        }
        return HomeGymPeriodCalculator.DEFAULT_STANDARD_LIMIT;
    }

    private String safeGymName(Long gymId, Long packageId) {
        try {
            return catalogClient.getHomeGymInfo(gymId, packageId).name();
        } catch (Exception e) {
            log.debug("Could not resolve gym name for {}: {}", gymId, e.getMessage());
            return null;
        }
    }

    private String messageKey(CheckInDecision decision) {
        return switch (decision) {
            case ENTERED_HOME_UNLIMITED -> "home_gym.entered_home_unlimited";
            case ENTERED_STANDARD -> "home_gym.standard_mode.note";
            case ENTERED_OFFER_HOME_GYM -> "home_gym.first_qr_offer";
            case CONFIRMATION_REQUIRED -> "home_gym.switch_warning";
            case OTHER_GYMS_LOCKED -> "home_gym.other_gyms_locked";
            case VISIT_LIMIT_EXCEEDED -> "qr.limit_reached";
            case DAILY_LIMIT_REACHED -> "home_gym.daily_limit";
            case HOME_MONTHLY_LIMIT_REACHED -> "home_gym.monthly_limit";
            case TIER_NOT_ALLOWED_AT_THIS_TIME -> "qr.tier_not_allowed";
            case SUBSCRIPTION_FROZEN -> "error.subscription_frozen";
            case NO_ACTIVE_SUBSCRIPTION -> "home_gym.no_active_subscription";
        };
    }
}
