package az.fitnest.order.service.freeze;

import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionFreezeEntitlementRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-time backfill job: creates FreezeEntitlements for existing ACTIVE/FROZEN
 * subscriptions that were assigned before BRD v1.1 and therefore have no entitlement row.
 *
 * BA blocker (freeze.txt §9): confirm whether backfill should run at all.
 * Until BA confirms, this job is disabled (BACKFILL_ENABLED=false by default).
 *
 * Trigger:
 *   - Automatic: @Scheduled runs once at startup if enabled (fixedDelay very large).
 *   - Manual: call runBackfill() from admin endpoint if needed.
 *
 * Idempotent: FreezeEntitlementService.createForSubscription skips if already exists.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FreezeEntitlementBackfillJob {

    private final SubscriptionFreezeEntitlementRepository entitlementRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPackageRepository packageRepository;
    private final FreezeEntitlementService entitlementService;
    private final FreezeTierPolicyProvider tierPolicyProvider;

    /**
     * Runs once at startup (large fixedDelay ensures it won't repeat).
     * Set FREEZE_BACKFILL_ENABLED=true env var to activate.
     * Default: disabled via condition below.
     */
    @Scheduled(fixedDelayString = "${freeze.backfill.initial-delay-ms:86400000}",
               initialDelayString = "${freeze.backfill.delay-ms:3600000}")
    @Transactional
    public void runBackfill() {
        String enabled = System.getenv("FREEZE_BACKFILL_ENABLED");
        if (!"true".equalsIgnoreCase(enabled)) {
            log.debug("FreezeEntitlementBackfillJob skipped (FREEZE_BACKFILL_ENABLED != true)");
            return;
        }

        log.info("Starting FreezeEntitlement backfill...");
        List<Long> subIds = entitlementRepository.findSubscriptionIdsWithoutEntitlement();
        log.info("Found {} subscriptions without entitlement", subIds.size());

        int created = 0;
        int skipped = 0;

        for (Long subId : subIds) {
            try {
                Subscription sub = subscriptionRepository.findById(subId).orElse(null);
                if (sub == null) { skipped++; continue; }

                // Resolve package name for tier detection
                var pkg = sub.getPackageId() != null
                        ? packageRepository.findById(sub.getPackageId()).orElse(null)
                        : null;
                String packageName = pkg != null ? pkg.getName() : "";

                if (!tierPolicyProvider.qualifiesForEntitlement(packageName)) {
                    log.debug("subscriptionId={} package='{}' does not qualify, skipping", subId, packageName);
                    skipped++;
                    continue;
                }

                var result = entitlementService.createForSubscription(
                        sub, packageName, tierPolicyProvider.getPolicyVersion());
                if (result != null) created++;
                else skipped++;

            } catch (Exception e) {
                log.error("Backfill failed for subscriptionId={}: {}", subId, e.getMessage());
                skipped++;
            }
        }

        log.info("FreezeEntitlement backfill complete: created={}, skipped={}", created, skipped);
    }
}
