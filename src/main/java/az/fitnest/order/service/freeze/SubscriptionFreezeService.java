package az.fitnest.order.service.freeze;

import az.fitnest.order.dto.freeze.*;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.model.enums.FreezeStatus;
import az.fitnest.order.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionFreezeService {

    private final SubscriptionFreezeRepository freezeRepository;
    private final SubscriptionFreezeEntitlementRepository entitlementRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final FreezeEntitlementService entitlementService;
    private final FreezePreviewService previewService;
    private final FreezeIdempotencyService idempotencyService;
    private final FreezeAuditEventRepository auditEventRepository;
    private final FreezeOutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public FreezeEligibilityResponse getEligibility(Long userId, Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));

        if (!sub.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Subscription does not belong to user");
        }

        Optional<SubscriptionFreeze> activeFreeze = freezeRepository.findBySubscriptionIdAndStatus(subscriptionId, FreezeStatus.ACTIVE);
        if (activeFreeze.isPresent()) {
            return FreezeEligibilityResponse.builder()
                    .eligible(false)
                    .reason("error.freeze.already_frozen")
                    .isCurrentlyFrozen(true)
                    .activeFreezeId(activeFreeze.get().getId())
                    .build();
        }

        if (!"ACTIVE".equalsIgnoreCase(sub.getStatus())) {
            return FreezeEligibilityResponse.builder()
                    .eligible(false)
                    .reason("error.subscription_not_active")
                    .isCurrentlyFrozen(false)
                    .build();
        }

        if (sub.getEndAt() != null && !sub.getEndAt().isAfter(LocalDateTime.now())) {
            return FreezeEligibilityResponse.builder()
                    .eligible(false)
                    .reason("error.subscription_expired")
                    .isCurrentlyFrozen(false)
                    .build();
        }

        SubscriptionFreezeEntitlement entitlement = entitlementService.getBySubscriptionId(subscriptionId).orElse(null);
        int totalDays = entitlement != null ? entitlement.getTotalDays() : 0;
        int consumedDays = entitlement != null ? entitlement.getConsumedDays() : 0;
        int reservedDays = entitlement != null ? entitlement.getReservedDays() : 0;
        int availableDays = Math.max(0, totalDays - consumedDays - reservedDays);

        if (availableDays <= 0) {
            return FreezeEligibilityResponse.builder()
                    .eligible(false)
                    .reason("error.freeze_days_exhausted")
                    .totalDays(totalDays)
                    .consumedDays(consumedDays)
                    .reservedDays(reservedDays)
                    .availableDays(0)
                    .isCurrentlyFrozen(false)
                    .build();
        }

        return FreezeEligibilityResponse.builder()
                .eligible(true)
                .totalDays(totalDays)
                .consumedDays(consumedDays)
                .reservedDays(reservedDays)
                .availableDays(availableDays)
                .isCurrentlyFrozen(false)
                .build();
    }

    @Transactional
    public FreezePreviewResponse previewFreeze(Long userId, Long subscriptionId, int days) {
        FreezeEligibilityResponse eligibility = getEligibility(userId, subscriptionId);
        if (!eligibility.isEligible()) {
            throw new IllegalStateException(eligibility.getReason());
        }

        if (days > eligibility.getAvailableDays()) {
            throw new IllegalArgumentException("error.freeze.limit_exceeded");
        }

        Subscription sub = subscriptionRepository.findById(subscriptionId).orElseThrow();
        return previewService.createPreview(userId, sub, days);
    }

    @Transactional
    public FreezeCommitResponse commitFreeze(Long userId, Long subscriptionId, FreezeCommitRequest request, String idempotencyKey) {
        String endpoint = "POST /subscriptions/" + subscriptionId + "/freezes";

        // Idempotency check
        Optional<FreezeIdempotencyRecord> cached = idempotencyService.findRecord(userId, endpoint, idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), FreezeCommitResponse.class);
            } catch (Exception e) {
                log.error("Failed to parse cached response for idempotencyKey={}", idempotencyKey);
            }
        }

        FreezePreview preview = previewService.getValidPreview(Long.parseLong(request.getPreviewId()), userId);
        if (!preview.getSubscriptionId().equals(subscriptionId)) {
            throw new IllegalArgumentException("Preview subscription mismatch");
        }

        // Pessimistic lock subscription
        Subscription sub = subscriptionRepository.findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));

        if (!"ACTIVE".equalsIgnoreCase(sub.getStatus())) {
            throw new IllegalStateException("error.subscription_not_active");
        }

        Optional<SubscriptionFreeze> activeFreeze = freezeRepository.findBySubscriptionIdAndStatus(subscriptionId, FreezeStatus.ACTIVE);
        if (activeFreeze.isPresent()) {
            throw new IllegalStateException("error.freeze.already_frozen");
        }

        SubscriptionFreezeEntitlement entitlement = entitlementRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Freeze entitlement not found"));

        int available = entitlement.getAvailableDays();
        if (preview.getRequestedDays() > available) {
            throw new IllegalStateException("error.freeze.limit_exceeded");
        }

        // Reserve days in entitlement
        entitlement.setReservedDays(entitlement.getReservedDays() + preview.getRequestedDays());
        entitlementRepository.save(entitlement);

        LocalDateTime now = LocalDateTime.now();

        // Create freeze row
        SubscriptionFreeze freeze = SubscriptionFreeze.builder()
                .subscriptionId(subscriptionId)
                .userId(userId)
                .requestedDays(preview.getRequestedDays())
                .startAt(now)
                .planEndAt(preview.getPlanEndAt())
                .expiryBefore(sub.getEndAt())
                .status(FreezeStatus.ACTIVE)
                .build();

        freeze = freezeRepository.save(freeze);

        // Update subscription status
        sub.setStatus("FROZEN");
        sub.setFrozenAt(now);
        sub.setUnfreezesAt(preview.getPlanEndAt());
        subscriptionRepository.save(sub);

        // Audit log
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestedDays", preview.getRequestedDays());
        payload.put("planEndAt", preview.getPlanEndAt().toString());

        auditEventRepository.save(FreezeAuditEvent.builder()
                .freezeId(freeze.getId())
                .subscriptionId(subscriptionId)
                .userId(userId)
                .eventType("FREEZE_COMMITTED")
                .payload(payload)
                .build());

        // Outbox event
        outboxEventRepository.save(FreezeOutboxEvent.builder()
                .eventType("subscription_frozen")
                .userId(userId)
                .freezeId(freeze.getId())
                .payload(payload)
                .published(false)
                .build());

        FreezeCommitResponse response = FreezeCommitResponse.builder()
                .freezeId(freeze.getId())
                .subscriptionId(subscriptionId)
                .requestedDays(freeze.getRequestedDays())
                .startAt(freeze.getStartAt())
                .planEndAt(freeze.getPlanEndAt())
                .expiryBefore(freeze.getExpiryBefore())
                .expiryAfter(preview.getExpiryAfter())
                .status(freeze.getStatus())
                .build();

        idempotencyService.saveRecord(userId, endpoint, idempotencyKey, request, response);

        return response;
    }

    @Transactional(readOnly = true)
    public ResumePreviewResponse resumePreview(Long userId, Long freezeId) {
        SubscriptionFreeze freeze = freezeRepository.findById(freezeId)
                .orElseThrow(() -> new IllegalArgumentException("Freeze record not found"));

        if (!freeze.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Freeze does not belong to user");
        }

        if (freeze.getStatus() != FreezeStatus.ACTIVE) {
            throw new IllegalStateException("error.freeze.not_frozen");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean isPlanEndReached = freeze.isPlanEndReached();

        long secondsElapsed = Math.max(0, Duration.between(freeze.getStartAt(), now).getSeconds());
        int usedDays = isPlanEndReached ? freeze.getRequestedDays() : Math.min(freeze.getRequestedDays(), Math.max(1, (int) Math.ceil(secondsElapsed / 86400.0)));
        int returnedDays = Math.max(0, freeze.getRequestedDays() - usedDays);
        LocalDateTime actualEndAt = isPlanEndReached ? freeze.getPlanEndAt() : now;
        LocalDateTime newExpiryDate = freeze.getExpiryBefore() != null ? freeze.getExpiryBefore().plusSeconds(secondsElapsed) : null;

        return ResumePreviewResponse.builder()
                .freezeId(freeze.getId())
                .subscriptionId(freeze.getSubscriptionId())
                .requestedDays(freeze.getRequestedDays())
                .usedDays(usedDays)
                .returnedDays(returnedDays)
                .startAt(freeze.getStartAt())
                .actualEndAt(actualEndAt)
                .expiryBefore(freeze.getExpiryBefore())
                .newExpiryDate(newExpiryDate)
                .isPlanEndReached(isPlanEndReached)
                .build();
    }

    @Transactional
    public ResumeCommitResponse resumeCommit(Long userId, Long freezeId, ResumeCommitRequest request, String idempotencyKey) {
        String endpoint = "POST /freezes/" + freezeId + "/resume";

        Optional<FreezeIdempotencyRecord> cached = idempotencyService.findRecord(userId, endpoint, idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), ResumeCommitResponse.class);
            } catch (Exception e) {
                log.error("Failed to parse cached resume response for idempotencyKey={}", idempotencyKey);
            }
        }

        SubscriptionFreeze freeze = freezeRepository.findByIdForUpdate(freezeId)
                .orElseThrow(() -> new IllegalArgumentException("Freeze record not found"));

        if (!freeze.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Freeze does not belong to user");
        }

        if (freeze.getStatus() != FreezeStatus.ACTIVE) {
            throw new IllegalStateException("error.freeze.not_frozen");
        }

        LocalDateTime now = LocalDateTime.now();
        long secondsElapsed = Math.max(0, Duration.between(freeze.getStartAt(), now).getSeconds());
        int usedDays = Math.min(freeze.getRequestedDays(), Math.max(1, (int) Math.ceil(secondsElapsed / 86400.0)));

        if (request != null && request.getExpectedUsedDays() != null && request.getExpectedUsedDays() != usedDays) {
            throw new IllegalStateException("error.freeze.preview_changed");
        }

        int returnedDays = Math.max(0, freeze.getRequestedDays() - usedDays);
        LocalDateTime expiryAfter = freeze.getExpiryBefore() != null ? freeze.getExpiryBefore().plusSeconds(secondsElapsed) : now;

        freeze.setStatus(FreezeStatus.ENDED_EARLY);
        freeze.setEndedBy("USER");
        freeze.setActualEndAt(now);
        freeze.setUsedDays(usedDays);
        freeze.setReturnedDays(returnedDays);
        freeze.setExpiryAfter(expiryAfter);
        freezeRepository.save(freeze);

        // Update entitlement budget
        SubscriptionFreezeEntitlement entitlement = entitlementRepository.findBySubscriptionIdForUpdate(freeze.getSubscriptionId())
                .orElseThrow(() -> new IllegalStateException("Freeze entitlement missing"));

        entitlement.setReservedDays(Math.max(0, entitlement.getReservedDays() - freeze.getRequestedDays()));
        entitlement.setConsumedDays(entitlement.getConsumedDays() + usedDays);
        entitlementRepository.save(entitlement);

        // Unfreeze subscription
        Subscription sub = subscriptionRepository.findByIdForUpdate(freeze.getSubscriptionId())
                .orElseThrow(() -> new IllegalStateException("Subscription missing"));

        sub.setStatus("ACTIVE");
        sub.setEndAt(expiryAfter);
        sub.setFrozenAt(null);
        sub.setUnfreezesAt(null);
        if (sub.getFrozenDaysUsed() != null) {
            sub.setFrozenDaysUsed(sub.getFrozenDaysUsed() + usedDays);
        } else {
            sub.setFrozenDaysUsed(usedDays);
        }
        subscriptionRepository.save(sub);

        // Audit log
        Map<String, Object> payload = new HashMap<>();
        payload.put("usedDays", usedDays);
        payload.put("returnedDays", returnedDays);
        payload.put("expiryAfter", expiryAfter.toString());

        auditEventRepository.save(FreezeAuditEvent.builder()
                .freezeId(freeze.getId())
                .subscriptionId(freeze.getSubscriptionId())
                .userId(userId)
                .eventType("FREEZE_RESUMED_EARLY")
                .payload(payload)
                .build());

        // Outbox event
        outboxEventRepository.save(FreezeOutboxEvent.builder()
                .eventType("subscription_unfrozen")
                .userId(userId)
                .freezeId(freeze.getId())
                .payload(payload)
                .published(false)
                .build());

        ResumeCommitResponse response = ResumeCommitResponse.builder()
                .freezeId(freeze.getId())
                .subscriptionId(freeze.getSubscriptionId())
                .requestedDays(freeze.getRequestedDays())
                .usedDays(usedDays)
                .returnedDays(returnedDays)
                .startAt(freeze.getStartAt())
                .actualEndAt(now)
                .expiryBefore(freeze.getExpiryBefore())
                .newExpiryDate(expiryAfter)
                .isPlanEndReached(false)
                .build();

        idempotencyService.saveRecord(userId, endpoint, idempotencyKey, request, response);

        return response;
    }

    @Transactional(readOnly = true)
    public List<FreezeRecordDto> listUserFreezes(Long userId) {
        return freezeRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public FreezeRecordDto getFreezeById(Long userId, Long freezeId) {
        SubscriptionFreeze freeze = freezeRepository.findById(freezeId)
                .orElseThrow(() -> new IllegalArgumentException("Freeze record not found"));

        if (!freeze.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Freeze does not belong to user");
        }

        return mapToDto(freeze);
    }

    private FreezeRecordDto mapToDto(SubscriptionFreeze f) {
        return FreezeRecordDto.builder()
                .id(f.getId())
                .subscriptionId(f.getSubscriptionId())
                .userId(f.getUserId())
                .requestedDays(f.getRequestedDays())
                .usedDays(f.getUsedDays())
                .returnedDays(f.getReturnedDays())
                .startAt(f.getStartAt())
                .planEndAt(f.getPlanEndAt())
                .actualEndAt(f.getActualEndAt())
                .expiryBefore(f.getExpiryBefore())
                .expiryAfter(f.getExpiryAfter())
                .status(f.getStatus())
                .endedBy(f.getEndedBy())
                .createdAt(f.getCreatedAt())
                .build();
    }
}
