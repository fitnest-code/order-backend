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

        LocalDateTime newEndAt = lockedFreeze.getExpiryBefore() != null
                ? lockedFreeze.getExpiryBefore().plusDays(requestedDays)
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

        // Outbox event
        outboxEventRepository.save(FreezeOutboxEvent.builder()
                .eventType("subscription_unfrozen")
                .userId(lockedFreeze.getUserId())
                .freezeId(lockedFreeze.getId())
                .payload(payload)
                .published(false)
                .build());

        log.info("Freeze completed for freezeId={}, subscriptionId={}, newEndAt={}", lockedFreeze.getId(), lockedFreeze.getSubscriptionId(), newEndAt);
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
}
