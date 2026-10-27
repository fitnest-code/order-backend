package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.dto.homegym.HomeGymContext;
import az.fitnest.order.dto.homegym.HomeGymResponse;
import az.fitnest.order.dto.homegym.HomeGymSelectRequest;
import az.fitnest.order.dto.homegym.HomeGymVisitPage;
import az.fitnest.order.dto.homegym.HomeGymVisitResponse;
import az.fitnest.order.event.SubscriptionEvent;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.HomeGymException;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.HomeGymEndReason;
import az.fitnest.order.model.enums.HomeGymSource;
import az.fitnest.order.model.enums.HomeGymStatus;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.HomeGymIdempotencyRepository;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HomeGymService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionHomeGymRepository homeGymRepository;
    private final GymVisitRepository gymVisitRepository;
    private final HomeGymIdempotencyRepository idempotencyRepository;
    private final CatalogServiceGrpcClient catalogClient;
    private final HomeGymPeriodCalculator periodCalculator;
    private final SubscriptionEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------------
    // Read model
    // ---------------------------------------------------------------------

    @Transactional
    public HomeGymResponse getHomeGym(Long userId, Long subscriptionId) {
        Subscription subscription = requireSubscription(userId, subscriptionId, false);
        reconcileEntryMode(subscription);
        return buildResponse(subscription);
    }

    @Transactional
    public HomeGymContext getContext(Long userId, Long subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (subscription == null || (userId != null && userId > 0 && !userId.equals(subscription.getUserId()))) {
            return new HomeGymContext(subscriptionId, userId, null, null, null, null, 0, 0, null, null, false);
        }
        reconcileEntryMode(subscription);
        HomeGymPeriodCalculator period = periodCalculator;
        LocalDate periodStart = period.periodStart(subscription.getStartAt());
        SubscriptionHomeGym active = homeGymRepository
                .findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(subscriptionId, HomeGymStatus.ACTIVE)
                .orElse(null);
        long homeVisits = active == null ? 0
                : gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                        subscriptionId, periodStart, VisitEntryType.HOME_UNLIMITED, active.getGymId());
        long standardVisits = gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                subscriptionId, periodStart, VisitEntryType.STANDARD);
        if (EntryMode.STANDARD == subscription.getEntryMode()) {
            standardVisits += gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                    subscriptionId, periodStart, VisitEntryType.HOME_UNLIMITED);
        }
        boolean activeSubscription = "ACTIVE".equals(subscription.getStatus())
                && !period.isExpired(subscription.getEndAt());
        return new HomeGymContext(
                subscription.getSubscriptionId(),
                subscription.getUserId(),
                subscription.getPackageId(),
                subscription.getStatus(),
                subscription.getEntryMode() != null ? subscription.getEntryMode().name() : EntryMode.HOME_GYM_PENDING.name(),
                active != null ? active.getGymId() : null,
                homeVisits,
                standardVisits,
                period.toOffset(period.periodEndsAt(subscription.getStartAt())),
                period.toOffset(subscription.getEndAt()),
                activeSubscription);
    }

    @Transactional(readOnly = true)
    public HomeGymVisitPage getVisits(Long userId, Long subscriptionId, int page, int size) {
        Subscription subscription = requireSubscription(userId, subscriptionId, false);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        var visitPage = gymVisitRepository.findBySubscriptionIdAndEntryTypeOrderByCheckedInAtDesc(
                subscription.getSubscriptionId(), VisitEntryType.HOME_UNLIMITED,
                PageRequest.of(safePage - 1, safeSize));
        List<HomeGymVisitResponse> items = new ArrayList<>();
        // Resolve each gym name once per page instead of once per visit.
        java.util.Map<Long, String> gymNames = new java.util.HashMap<>();
        for (GymVisit visit : visitPage.getContent()) {
            String gymName = gymNames.computeIfAbsent(visit.getGymId(),
                    id -> catalogClient.getHomeGymInfo(id, subscription.getPackageId()).name());
            items.add(new HomeGymVisitResponse(
                    visit.getId(),
                    visit.getGymId(),
                    gymName,
                    periodCalculator.toOffset(visit.getCheckedInAt()),
                    visit.getEntryType().name(),
                    visit.getSinglePrice()));
        }
        return new HomeGymVisitPage(items, safePage, safeSize, visitPage.getTotalElements());
    }

    // ---------------------------------------------------------------------
    // Selection / change
    // ---------------------------------------------------------------------

    @Transactional
    public HomeGymResponse selectHomeGym(Long userId, Long subscriptionId, HomeGymSelectRequest request,
                                        String idempotencyKey, boolean admin) {
        if (request.source() == null) {
            throw new HomeGymException("INVALID_SOURCE", HttpStatus.BAD_REQUEST,
                    "error.home_gym.invalid_source");
        }
        if (request.source() == HomeGymSource.ADMIN && !admin) {
            throw new HomeGymException("INVALID_SOURCE", HttpStatus.BAD_REQUEST, "error.home_gym.invalid_source");
        }
        if (!admin && (idempotencyKey == null || idempotencyKey.isBlank())) {
            throw new BadRequestException("error.idempotency_key_required");
        }

        String requestHash = hash(request.gymId() + "|" + request.source().name());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            HomeGymResponse replay = replay(idempotencyKey, userId, subscriptionId, requestHash);
            if (replay != null) {
                return replay;
            }
        }

        Subscription subscription = requireSubscription(admin ? null : userId, subscriptionId, true);
        ensureSelectable(subscription);

        var info = catalogClient.getHomeGymInfo(request.gymId(), subscription.getPackageId());
        if (!info.found()) {
            throw new ResourceNotFoundException("error.home_gym.gym_not_found");
        }
        if (!info.supportsPackage()) {
            throw new HomeGymException("GYM_NOT_IN_PACKAGE", HttpStatus.UNPROCESSABLE_ENTITY,
                    "error.home_gym.gym_not_in_package");
        }
        if (!info.homeEligible()) {
            throw new HomeGymException("GYM_HOME_DISABLED", HttpStatus.UNPROCESSABLE_ENTITY,
                    "error.home_gym.gym_home_disabled");
        }

        LocalDateTime now = periodCalculator.now();
        LocalDate periodStart = periodCalculator.periodStart(subscription.getStartAt(), now.toLocalDate());
        SubscriptionHomeGym active = homeGymRepository.findActiveForUpdate(subscriptionId).orElse(null);

        Long previousGymId = null;
        if (active != null) {
            if (active.getGymId().equals(request.gymId())) {
                return buildResponse(subscription);
            }
            long visitsToActive = countVisitsAtGym(subscription.getSubscriptionId(), periodStart,
                    active.getGymId());
            if (visitsToActive > 0) {
                throw new HomeGymException("HOME_GYM_CHANGE_LOCKED", HttpStatus.CONFLICT,
                        "error.home_gym.change_locked");
            }
            previousGymId = active.getGymId();
            active.setStatus(HomeGymStatus.CHANGED);
            active.setEndedAt(now);
            active.setEndReason(HomeGymEndReason.CHANGE);
            homeGymRepository.save(active);
        }

        SubscriptionHomeGym selected = SubscriptionHomeGym.builder()
                .subscriptionId(subscriptionId)
                .userId(subscription.getUserId())
                .gymId(request.gymId())
                .status(HomeGymStatus.ACTIVE)
                .source(request.source())
                .selectedAt(now)
                .build();
        homeGymRepository.save(selected);

        EntryMode currentMode = subscription.getEntryMode() != null
                ? subscription.getEntryMode() : EntryMode.HOME_GYM_PENDING;
        subscription.setEntryMode(EntryMode.HOME_GYM);
        if (request.source() == HomeGymSource.FIRST_QR && currentMode == EntryMode.HOME_GYM_PENDING) {
            promoteFirstQrVisit(subscription, request.gymId(), now);
        }
        subscriptionRepository.save(subscription);

        SubscriptionEvent event = new SubscriptionEvent(
                subscription.getUserId(),
                previousGymId == null ? "HOME_GYM_SELECTED" : "HOME_GYM_CHANGED",
                subscriptionId);
        event.setGymId(request.gymId());
        event.setPreviousGymId(previousGymId);
        event.setGymName(info.name());
        var validUntil = periodCalculator.toOffset(subscription.getEndAt());
        event.setValidUntil(validUntil != null ? validUntil.toString() : null);
        eventPublisher.publishSubscriptionEvent(subscription.getUserId(), event.getAction(),
                subscriptionId, event);

        HomeGymResponse response = buildResponse(subscription);
        remember(idempotencyKey, userId, subscriptionId, requestHash, response);
        return response;
    }

    /**
     * R8/FIRST_QR: the initial offer visit was recorded as STANDARD so the counters stay
     * correct if the offer is declined. Accepting the offer promotes it to HOME_UNLIMITED
     * and gives the unused unit back to the legacy remaining-limit counter.
     */
    private void promoteFirstQrVisit(Subscription subscription, Long gymId, LocalDateTime now) {
        LocalDate periodStart = periodCalculator.periodStart(subscription.getStartAt(), now.toLocalDate());
        GymVisit visit = gymVisitRepository
                .findFirstBySubscriptionIdAndGymIdAndEntryTypeAndPeriodStartOrderByCheckedInAtDesc(
                        subscription.getSubscriptionId(), gymId, VisitEntryType.STANDARD, periodStart)
                .orElse(null);
        if (visit == null || Boolean.FALSE.equals(visit.getCountsTowardsLimit())) {
            return;
        }
        visit.setEntryType(VisitEntryType.HOME_UNLIMITED);
        visit.setHomeGymId(gymId);
        gymVisitRepository.save(visit);

        if (subscription.getRemainingLimit() != null) {
            int restored = subscription.getRemainingLimit() + 1;
            if (subscription.getTotalLimit() != null && subscription.getTotalLimit() > 0) {
                restored = Math.min(restored, subscription.getTotalLimit());
            }
            subscription.setRemainingLimit(restored);
        }
        if ("FINISHED".equals(subscription.getStatus()) && subscription.getEntryMode() == EntryMode.HOME_GYM) {
            subscription.setStatus("ACTIVE");
        }
    }

    // ---------------------------------------------------------------------
    // R13 upgrade hook
    // ---------------------------------------------------------------------

    @Transactional
    public void onUpgrade(Subscription subscription, Long newPackageId, String newPackageName) {
        SubscriptionHomeGym active = homeGymRepository
                .findActiveForUpdate(subscription.getSubscriptionId()).orElse(null);
        if (active == null) {
            return;
        }
        var info = catalogClient.getHomeGymInfo(active.getGymId(), newPackageId);
        if (info.found() && info.supportsPackage()) {
            return;
        }
        endHomeGym(subscription, active, HomeGymEndReason.UPGRADE_NOT_IN_PACKAGE, periodCalculator.now(), info.name());
    }

    @Transactional
    public void onGymHomeDisabled(Long gymId, Long campaignId, java.util.Map<String, String> title,
                                  java.util.Map<String, String> body, java.util.List<Long> recipients) {
        List<Long> subscriptionIds = homeGymRepository.findActiveSubscriptionIdsByGymId(gymId);
        for (Long subscriptionId : subscriptionIds) {
            Subscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
            if (subscription == null) {
                continue;
            }
            SubscriptionHomeGym active = homeGymRepository.findActiveForUpdate(subscriptionId).orElse(null);
            if (active == null || !gymId.equals(active.getGymId())) {
                continue;
            }
            LocalDateTime periodEnd = periodCalculator.periodEndsAt(subscription.getStartAt());
            active.setEndsAt(periodEnd);
            active.setEndReason(HomeGymEndReason.GYM_HOME_DISABLED);
            homeGymRepository.save(active);

            SubscriptionEvent event = new SubscriptionEvent(
                    subscription.getUserId(), "HOME_GYM_DISABLED_BY_GYM", subscriptionId);
            event.setGymId(gymId);
            event.setCampaignId(campaignId);
            event.setTitle(title);
            event.setBody(body);
            event.setRecipients(recipients);
            eventPublisher.publishSubscriptionEvent(subscription.getUserId(), event.getAction(), subscriptionId, event);
        }
    }

    // ---------------------------------------------------------------------
    // Scheduled expiry (R18 / subscription end)
    // ---------------------------------------------------------------------

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void expireDueHomeGyms() {
        LocalDateTime now = periodCalculator.now();
        for (SubscriptionHomeGym row : homeGymRepository.findDueForScheduledEnd(now)) {
            Subscription subscription = subscriptionRepository.findById(row.getSubscriptionId()).orElse(null);
            if (subscription == null) {
                continue;
            }
            HomeGymEndReason reason = row.getEndReason() != null
                    ? row.getEndReason() : HomeGymEndReason.GYM_HOME_DISABLED;
            endHomeGym(subscription, row, reason, now, null);
        }
    }

    @Scheduled(cron = "0 15 0 * * *")
    @Transactional
    public void expireEndedSubscriptionHomeGyms() {
        LocalDateTime now = periodCalculator.now();
        for (SubscriptionHomeGym row : homeGymRepository.findByStatusAndEndReasonIsNull(HomeGymStatus.ACTIVE)) {
            Subscription subscription = subscriptionRepository.findById(row.getSubscriptionId()).orElse(null);
            if (subscription == null) {
                continue;
            }
            boolean expired = subscription.getEndAt() != null && subscription.getEndAt().isBefore(now);
            // FROZEN keeps the home gym (R10/R12) and a FINISHED subscription that has not
            // expired yet keeps unlimited home access until its end date (R3). Only terminal
            // states really end the home gym.
            boolean terminated = "CANCELLED".equals(subscription.getStatus())
                    || "EXPIRED".equals(subscription.getStatus());
            if (expired || terminated) {
                endHomeGym(subscription, row, HomeGymEndReason.SUBSCRIPTION_ENDED, now, null);
            }
        }
    }

    private void endHomeGym(Subscription subscription, SubscriptionHomeGym active,
                            HomeGymEndReason reason, LocalDateTime now, String gymName) {
        if (gymName == null) {
            gymName = safeGymName(active.getGymId(), subscription.getPackageId());
        }
        active.setStatus(HomeGymStatus.ENDED);
        active.setEndedAt(now);
        active.setEndReason(reason);
        active.setEndsAt(null);
        homeGymRepository.save(active);
        subscription.setEntryMode(EntryMode.STANDARD);
        subscriptionRepository.save(subscription);

        SubscriptionEvent event = new SubscriptionEvent(
                subscription.getUserId(), "HOME_GYM_ENDED", subscription.getSubscriptionId());
        event.setGymId(active.getGymId());
        event.setGymName(gymName);
        event.setEndReason(reason.name());
        eventPublisher.publishSubscriptionEvent(subscription.getUserId(), event.getAction(),
                subscription.getSubscriptionId(), event);
    }

    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> getGymSubscribers(Long gymId) {
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        for (Long subscriptionId : homeGymRepository.findActiveSubscriptionIdsByGymId(gymId)) {
            subscriptionRepository.findById(subscriptionId).ifPresent(subscription -> {
                SubscriptionHomeGym active = homeGymRepository
                        .findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                                subscriptionId, HomeGymStatus.ACTIVE)
                        .orElse(null);
                java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("userId", subscription.getUserId());
                item.put("subscriptionId", subscription.getSubscriptionId());
                item.put("gymId", gymId);
                item.put("selectedAt", active != null && active.getSelectedAt() != null
                        ? active.getSelectedAt().toString() : null);
                item.put("endsAt", active != null && active.getEndsAt() != null
                        ? active.getEndsAt().toString() : null);
                item.put("source", active != null && active.getSource() != null
                        ? active.getSource().name() : null);
                result.add(item);
            });
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Long> resolveSubscriberUserIds(Long gymId) {
        List<Long> result = new ArrayList<>();
        for (Long subscriptionId : homeGymRepository.findActiveSubscriptionIdsByGymId(gymId)) {
            subscriptionRepository.findById(subscriptionId)
                    .map(Subscription::getUserId)
                    .filter(java.util.Objects::nonNull)
                    .filter(userId -> !result.contains(userId))
                    .ifPresent(result::add);
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private Subscription requireSubscription(Long userId, Long subscriptionId, boolean forUpdate) {
        if (subscriptionId == null) {
            throw new ResourceNotFoundException("error.subscription_not_found");
        }
        Subscription subscription = forUpdate
                ? subscriptionRepository.findByIdForUpdate(subscriptionId).orElse(null)
                : subscriptionRepository.findById(subscriptionId).orElse(null);
        if (subscription == null) {
            throw new ResourceNotFoundException("error.subscription_not_found");
        }
        if (userId != null && !userId.equals(subscription.getUserId())) {
            throw new HomeGymException("FORBIDDEN", HttpStatus.FORBIDDEN, "error.forbidden");
        }
        return subscription;
    }

    private void ensureSelectable(Subscription subscription) {
        if ("FROZEN".equals(subscription.getStatus())) {
            throw new HomeGymException("SUBSCRIPTION_FROZEN", HttpStatus.CONFLICT,
                    "error.subscription_frozen");
        }
        boolean active = ("ACTIVE".equals(subscription.getStatus())
                || "FINISHED".equals(subscription.getStatus()))
                && !periodCalculator.isExpired(subscription.getEndAt());
        if (!active) {
            throw new HomeGymException("SUBSCRIPTION_INACTIVE", HttpStatus.CONFLICT,
                    "error.home_gym.subscription_inactive");
        }
    }

    private void reconcileEntryMode(Subscription subscription) {
        if (subscription.getEntryMode() == null) {
            subscription.setEntryMode(EntryMode.HOME_GYM_PENDING);
        }
        if (subscription.getEntryMode() != EntryMode.HOME_GYM) {
            // An ACTIVE row is the source of truth; repair a partially applied selection
            // so GET /home-gym never reports a home gym in STANDARD/PENDING mode.
            if (homeGymRepository.existsBySubscriptionIdAndStatus(
                    subscription.getSubscriptionId(), HomeGymStatus.ACTIVE)) {
                subscription.setEntryMode(EntryMode.HOME_GYM);
                subscriptionRepository.save(subscription);
            }
            return;
        }
        boolean hasActive = homeGymRepository.existsBySubscriptionIdAndStatus(
                subscription.getSubscriptionId(), HomeGymStatus.ACTIVE);
        if (!hasActive) {
            boolean everSelected = homeGymRepository.existsBySubscriptionIdAndStatus(
                    subscription.getSubscriptionId(), HomeGymStatus.ENDED)
                    || homeGymRepository.existsBySubscriptionIdAndStatus(
                    subscription.getSubscriptionId(), HomeGymStatus.CHANGED)
                    || homeGymRepository.existsBySubscriptionIdAndStatus(
                    subscription.getSubscriptionId(), HomeGymStatus.REMOVED_BY_SWITCH);
            subscription.setEntryMode(everSelected ? EntryMode.STANDARD : EntryMode.HOME_GYM_PENDING);
            subscriptionRepository.save(subscription);
        }
    }

    private HomeGymResponse buildResponse(Subscription subscription) {
        LocalDateTime now = periodCalculator.now();
        LocalDate periodStart = periodCalculator.periodStart(subscription.getStartAt(), now.toLocalDate());
        int limit = resolveLimit(subscription);
        EntryMode mode = subscription.getEntryMode() != null
                ? subscription.getEntryMode() : EntryMode.HOME_GYM_PENDING;

        SubscriptionHomeGym active = homeGymRepository
                .findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                        subscription.getSubscriptionId(), HomeGymStatus.ACTIVE)
                .orElse(null);

        long homeVisits = active == null ? 0
                : gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                        subscription.getSubscriptionId(), periodStart,
                        VisitEntryType.HOME_UNLIMITED, active.getGymId());
        long standardVisits = gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                subscription.getSubscriptionId(), periodStart, VisitEntryType.STANDARD);
        if (mode == EntryMode.STANDARD) {
            standardVisits += gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                    subscription.getSubscriptionId(), periodStart, VisitEntryType.HOME_UNLIMITED);
        }

        HomeGymResponse.HomeGymInfo info = null;
        if (active != null) {
            var gymInfo = catalogClient.getHomeGymInfo(active.getGymId(), subscription.getPackageId());
            info = new HomeGymResponse.HomeGymInfo(
                    active.getGymId(),
                    gymInfo.name(),
                    periodCalculator.toOffset(active.getSelectedAt()),
                    gymInfo.availableHours(),
                    periodCalculator.toOffset(active.getEndsAt()));
        }

        Long homeVisitsForCurrent = active == null ? 0 : homeVisits;
        boolean otherGymsLocked = active != null && homeVisitsForCurrent >= HomeGymPeriodCalculator.DEFAULT_STANDARD_LIMIT;
        // R4: the home gym can only be changed while it has no visits at all in this
        // subscription month, including standard visits made before it was selected.
        long visitsToCurrent = active == null ? 0
                : countVisitsAtGym(subscription.getSubscriptionId(), periodStart, active.getGymId());
        boolean canChange = active != null && visitsToCurrent == 0;
        String blockedReason = active != null && visitsToCurrent > 0 ? "HOME_GYM_HAS_VISITS" : null;
        int used = (int) standardVisits;
        int remaining = Math.max(0, limit - used);

        return new HomeGymResponse(
                subscription.getSubscriptionId(),
                mode.name(),
                info,
                canChange,
                blockedReason,
                homeVisitsForCurrent,
                otherGymsLocked,
                new HomeGymResponse.StandardVisits(used, limit, remaining),
                periodCalculator.toOffset(periodCalculator.periodEndsAt(subscription.getStartAt())),
                periodCalculator.toOffset(subscription.getEndAt()),
                mode != EntryMode.HOME_GYM);
    }

    public int resolveLimit(Subscription subscription) {
        if (subscription != null && subscription.getTotalLimit() != null && subscription.getTotalLimit() > 0) {
            return subscription.getTotalLimit();
        }
        return HomeGymPeriodCalculator.DEFAULT_STANDARD_LIMIT;
    }

    private long countVisitsAtGym(Long subscriptionId, LocalDate periodStart, Long gymId) {
        return gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                subscriptionId, periodStart, VisitEntryType.HOME_UNLIMITED, gymId)
                + gymVisitRepository.countCountableBySubscriptionAndPeriodAndTypeAndGym(
                subscriptionId, periodStart, VisitEntryType.STANDARD, gymId);
    }

    private String safeGymName(Long gymId, Long packageId) {
        try {
            return catalogClient.getHomeGymInfo(gymId, packageId).name();
        } catch (Exception e) {
            log.debug("Could not resolve gym name for {}: {}", gymId, e.getMessage());
            return null;
        }
    }

    private HomeGymResponse replay(String key, Long userId, Long subscriptionId, String requestHash) {
        var record = idempotencyRepository.findByIdempotencyKey(key).orElse(null);
        if (record == null) {
            return null;
        }
        if (!record.getUserId().equals(userId)
                || !record.getSubscriptionId().equals(subscriptionId)
                || !record.getRequestHash().equals(requestHash)) {
            throw new HomeGymException("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT,
                    "error.home_gym.idempotency_conflict");
        }
        if (record.getResponseJson() == null) {
            throw new HomeGymException("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT,
                    "error.home_gym.idempotency_conflict");
        }
        try {
            return objectMapper.readValue(record.getResponseJson(), HomeGymResponse.class);
        } catch (Exception e) {
            throw new HomeGymException("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT,
                    "error.home_gym.idempotency_conflict");
        }
    }

    private void remember(String key, Long userId, Long subscriptionId, String requestHash,
                          HomeGymResponse response) {
        if (key == null || key.isBlank()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("Could not serialize home-gym idempotent response for key {}: {}", key, e.getMessage());
            return;
        }
        idempotencyRepository.save(az.fitnest.order.model.entity.HomeGymIdempotency.builder()
                .idempotencyKey(key)
                .userId(userId)
                .subscriptionId(subscriptionId)
                .requestHash(requestHash)
                .responseJson(json)
                .createdAt(periodCalculator.now())
                .build());
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
