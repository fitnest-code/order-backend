package az.fitnest.order.service.visithistory;

import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.VisitRejectCode;
import az.fitnest.order.repository.VisitAttemptRepository;
import az.fitnest.order.service.homegym.HomeGymPeriodCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Appends rejected QR attempts to the append-only {@code visit_attempt} table.
 *
 * <p>History must never break a check-in response, so every failure (missing
 * subscription, malformed values, database hiccup) is swallowed after logging.
 * The insert itself is idempotent on {@code qrScanId} at the database level.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisitAttemptRecorder {

    private final VisitAttemptRepository attemptRepository;
    private final CurrentSubscriptionResolver subscriptionResolver;
    private final HomeGymPeriodCalculator periodCalculator;

    @Transactional
    public void record(Subscription subscription,
                       Long userId,
                       Long gymId,
                       String gymName,
                       String packageCode,
                       VisitRejectCode rejectCode,
                       String qrScanId,
                       LocalDateTime scannedAt) {
        if (gymId == null || rejectCode == null) {
            return;
        }
        try {
            Subscription resolved = subscription != null ? subscription : subscriptionResolver.resolve(userId);
            if (resolved == null || resolved.getSubscriptionId() == null) {
                log.debug("Skipping {} attempt for gym {}: no subscription for user {}",
                        rejectCode, gymId, userId);
                return;
            }
            Long effectiveUserId = resolved.getUserId() != null ? resolved.getUserId() : userId;
            if (effectiveUserId == null) {
                return;
            }
            String mode = resolved.getEntryMode() == EntryMode.HOME_GYM
                    ? EntryMode.HOME_GYM.name() : EntryMode.STANDARD.name();
            String cleanQrScanId = qrScanId == null || qrScanId.isBlank() ? null : qrScanId.trim();

            attemptRepository.insertIfAbsent(
                    resolved.getSubscriptionId(),
                    effectiveUserId,
                    gymId,
                    truncate(gymName, 120),
                    truncate(packageCode, 32),
                    mode,
                    rejectCode.name(),
                    cleanQrScanId,
                    scannedAt != null ? scannedAt : periodCalculator.now());
        } catch (Exception e) {
            // Analytics-only write: a failure must not change the QR decision.
            log.warn("Could not record {} attempt for user {} at gym {}: {}",
                    rejectCode, userId, gymId, e.getMessage());
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
