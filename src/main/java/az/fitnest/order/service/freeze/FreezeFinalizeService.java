package az.fitnest.order.service.freeze;

import az.fitnest.order.model.entity.*;
import az.fitnest.order.model.enums.FreezeStatus;
import az.fitnest.order.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreezeFinalizeService {

    private final SubscriptionFreezeRepository freezeRepository;
    private final SubscriptionFreezeEntitlementRepository entitlementRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final FreezeAuditEventRepository auditEventRepository;
    private final FreezeOutboxEventRepository outboxEventRepository;

    @Transactional
    public void complete(SubscriptionFreeze freeze) {
        if (freeze == null || freeze.getStatus() != FreezeStatus.ACTIVE) {
            return;
        }

        SubscriptionFreeze lockedFreeze = freezeRepository.findByIdForUpdate(freeze.getId())
                .orElse(freeze);

        if (lockedFreeze.getStatus() != FreezeStatus.ACTIVE) {
            log.info("Freeze id={} is no longer ACTIVE (status={}), skipping completion", lockedFreeze.getId(), lockedFreeze.getStatus());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime planEndAt = lockedFreeze.getPlanEndAt();
        int requestedDays = lockedFreeze.getRequestedDays();

        lockedFreeze.setStatus(FreezeStatus.COMPLETED);
        lockedFreeze.setEndedBy("WORKER");
        lockedFreeze.setActualEndAt(planEndAt);
        lockedFreeze.setUsedDays(requestedDays);
        lockedFreeze.setReturnedDays(0);

        // FIX-10: BRD §2.3 — 1 freeze day = exactly 86400 seconds (not calendar plusDays)
        LocalDateTime newEndAt = lockedFreeze.getExpiryBefore() != null
                ? lockedFreeze.getExpiryBefore().plusSeconds((long) requestedDays * 86400L)
                : planEndAt;
        lockedFreeze.setExpiryAfter(newEndAt);

        freezeRepository.save(lockedFreeze);

        // Adjust entitlement
        Optional<SubscriptionFreezeEntitlement> entitlementOpt = entitlementRepository.findBySubscriptionIdForUpdate(lockedFreeze.getSubscriptionId());
        if (entitlementOpt.isPresent()) {
            SubscriptionFreezeEntitlement entitlement = entitlementOpt.get();
            entitlement.setReservedDays(Math.max(0, entitlement.getReservedDays() - requestedDays));
            entitlement.setConsumedDays(entitlement.getConsumedDays() + requestedDays);
            entitlementRepository.save(entitlement);
        }

        // Unfreeze subscription
        Optional<Subscription> subOpt = subscriptionRepository.findByIdForUpdate(lockedFreeze.getSubscriptionId());
        if (subOpt.isPresent()) {
            Subscription sub = subOpt.get();
            sub.setStatus("ACTIVE");
            sub.setEndAt(newEndAt);
            sub.setFrozenAt(null);
            sub.setUnfreezesAt(null);
            if (sub.getFrozenDaysUsed() != null) {
                sub.setFrozenDaysUsed(sub.getFrozenDaysUsed() + requestedDays);
            } else {
                sub.setFrozenDaysUsed(requestedDays);
            }
            subscriptionRepository.save(sub);
        }

        // Audit log
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestedDays", requestedDays);
        payload.put("usedDays", requestedDays);
        payload.put("newEndAt", newEndAt.toString());

        auditEventRepository.save(FreezeAuditEvent.builder()
                .freezeId(lockedFreeze.getId())
                .subscriptionId(lockedFreeze.getSubscriptionId())
                .userId(lockedFreeze.getUserId())
                .eventType("FREEZE_COMPLETED")
                .payload(payload)
                .build());

        // Outbox event (FIX-12: align event type with BRD — freeze_completed)
        outboxEventRepository.save(FreezeOutboxEvent.builder()
                .eventType("freeze_completed")
                .userId(lockedFreeze.getUserId())
                .freezeId(lockedFreeze.getId())
                .payload(payload)
                .published(false)
                .build());

        log.info("Freeze completed for freezeId={}, subscriptionId={}, newEndAt={}", lockedFreeze.getId(), lockedFreeze.getSubscriptionId(), newEndAt);

        // FIX-15: Update queued/PENDING successor subscriptions whose startAt predates the new endAt
        if (subOpt.isPresent()) {
            shiftSuccessorStartDates(subOpt.get().getUserId(), newEndAt);
        }
    }

    @Transactional
    public void terminateActive(Long subscriptionId, String endedBy) {
        Optional<SubscriptionFreeze> activeFreezeOpt = freezeRepository.findBySubscriptionIdAndStatus(subscriptionId, FreezeStatus.ACTIVE);
        if (activeFreezeOpt.isEmpty()) {
            return;
        }

        SubscriptionFreeze freeze = activeFreezeOpt.get();
        LocalDateTime now = LocalDateTime.now();

        freeze.setStatus(FreezeStatus.TERMINATED);
        freeze.setEndedBy(endedBy != null ? endedBy : "SYSTEM");
        freeze.setActualEndAt(now);
        freezeRepository.save(freeze);

        // Adjust entitlement
        Optional<SubscriptionFreezeEntitlement> entitlementOpt = entitlementRepository.findBySubscriptionIdForUpdate(subscriptionId);
        if (entitlementOpt.isPresent()) {
            SubscriptionFreezeEntitlement entitlement = entitlementOpt.get();
            entitlement.setReservedDays(Math.max(0, entitlement.getReservedDays() - freeze.getRequestedDays()));
            entitlementRepository.save(entitlement);
        }

        // Clean subscription legacy freeze markers
        Optional<Subscription> subOpt = subscriptionRepository.findByIdForUpdate(subscriptionId);
        if (subOpt.isPresent()) {
            Subscription sub = subOpt.get();
            sub.setFrozenAt(null);
            sub.setUnfreezesAt(null);
            subscriptionRepository.save(sub);
        }

        // Audit log
        Map<String, Object> payload = new HashMap<>();
        payload.put("endedBy", endedBy);
        payload.put("terminatedAt", now.toString());

        auditEventRepository.save(FreezeAuditEvent.builder()
                .freezeId(freeze.getId())
                .subscriptionId(subscriptionId)
                .userId(freeze.getUserId())
                .eventType("FREEZE_TERMINATED")
                .payload(payload)
                .build());

        // Outbox event
        outboxEventRepository.save(FreezeOutboxEvent.builder()
                .eventType("freeze_blocked")
                .userId(freeze.getUserId())
                .freezeId(freeze.getId())
                .payload(payload)
                .published(false)
                .build());

        log.info("Terminated active freeze id={} for subscriptionId={}, endedBy={}", freeze.getId(), subscriptionId, endedBy);
    }

    /**
     * FIX-15: After a freeze extends a subscription's endAt, any PENDING (queued) successor
     * subscriptions for the same user whose startAt falls before newEndAt are shifted forward.
     * This prevents subscription window overlap after freeze extension.
     *
     * @param userId     the user whose pending subscriptions should be checked
     * @param newEndAt   the updated end date of the current active subscription
     */
    @Transactional
    public void shiftSuccessorStartDates(Long userId, LocalDateTime newEndAt) {
        if (userId == null || newEndAt == null) return;

        java.util.List<Subscription> pendingSubs =
                subscriptionRepository.findByUserIdAndStatusOrderByStartAtDesc(userId, "PENDING");

        for (Subscription pending : pendingSubs) {
            if (pending.getStartAt() != null && pending.getStartAt().isBefore(newEndAt)) {
                LocalDateTime oldStart = pending.getStartAt();
                pending.setStartAt(newEndAt);
                // Preserve original duration: shift endAt by the same delta
                if (pending.getEndAt() != null && oldStart != null) {
                    long durationSeconds = java.time.Duration.between(oldStart, pending.getEndAt()).getSeconds();
                    pending.setEndAt(newEndAt.plusSeconds(durationSeconds));
                }
                subscriptionRepository.save(pending);
                log.info("FIX-15: Shifted PENDING subscriptionId={} startAt from {} to {} (endAt now={})",
                        pending.getSubscriptionId(), oldStart, newEndAt, pending.getEndAt());
            }
        }
    }
}
