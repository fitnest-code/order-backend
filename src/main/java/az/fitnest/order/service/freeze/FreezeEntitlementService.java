package az.fitnest.order.service.freeze;

import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionFreezeEntitlement;
import az.fitnest.order.repository.SubscriptionFreezeEntitlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Manages freeze day entitlement balances.
 *
 * Entitlement is created when a subscription is assigned or renewed.
 * Balances are adjusted atomically during freeze commit and finalize.
 *
 * Thread safety: all write methods use pessimistic SELECT FOR UPDATE to
 * prevent concurrent balance corruption. The @Version on the entity
 * provides an additional optimistic guard for reads-then-write paths.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FreezeEntitlementService {

    private final SubscriptionFreezeEntitlementRepository entitlementRepository;
    private final FreezeTierPolicyProvider tierPolicyProvider;
    private final az.fitnest.order.repository.SubscriptionRepository subscriptionRepository;
    private final az.fitnest.order.repository.SubscriptionPackageRepository packageRepository;

    // ─── Create ───────────────────────────────────────────────────────────────

    /**
     * Creates a freeze entitlement for a newly assigned/renewed subscription.
     * Idempotent: if an entitlement already exists for this subscriptionId, does nothing.
     *
     * @param subscription  the newly saved subscription
     * @param packageName   used to resolve tier policy (Bronze/Silver/Gold/Platinum)
     * @param policyVersion snapshot of the policy version for audit
     */
    @Transactional
    public SubscriptionFreezeEntitlement createForSubscription(
            Subscription subscription,
            String packageName,
            String policyVersion) {

        Long subId = subscription.getSubscriptionId();

        if (entitlementRepository.existsBySubscriptionId(subId)) {
            log.debug("Entitlement already exists for subscriptionId={}, skipping", subId);
            return entitlementRepository.findBySubscriptionId(subId).orElseThrow();
        }

        int totalDays = tierPolicyProvider.getAllowedDays(packageName);
        if (totalDays == 0) {
            // Trial / free / unrecognised tier — skip unless BA specifies otherwise
            log.info("Package '{}' does not qualify for freeze entitlement (subscriptionId={}). Skipping.",
                    packageName, subId);
            return null;
        }

        SubscriptionFreezeEntitlement entitlement = SubscriptionFreezeEntitlement.builder()
                .subscriptionId(subId)
                .userId(subscription.getUserId())
                .totalDays(totalDays)
                .consumedDays(0)
                .reservedDays(0)
                .policyVersion(policyVersion)
                .build();

        SubscriptionFreezeEntitlement saved = entitlementRepository.save(entitlement);
        log.info("Created freeze entitlement: subscriptionId={}, userId={}, totalDays={}, policy={}",
                subId, subscription.getUserId(), totalDays, policyVersion);
        return saved;
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    @Transactional
    public Optional<SubscriptionFreezeEntitlement> getBySubscriptionId(Long subscriptionId) {
        Optional<SubscriptionFreezeEntitlement> existing = entitlementRepository.findBySubscriptionId(subscriptionId);
        if (existing.isPresent()) {
            return existing;
        }

        // Lazy-provision for legacy active subscriptions (e.g. Bronze #170)
        return subscriptionRepository.findById(subscriptionId).flatMap(sub -> {
            String packageName = "Bronze";
            if (sub.getPackageId() != null) {
                var pkgOpt = packageRepository.findById(sub.getPackageId());
                if (pkgOpt.isPresent() && pkgOpt.get().getName() != null) {
                    packageName = pkgOpt.get().getName();
                }
            }
            int totalDays = tierPolicyProvider.getAllowedDays(packageName);
            if (totalDays <= 0) {
                totalDays = 2; // Default to 2 days for active subscriptions if package not found
            }
            SubscriptionFreezeEntitlement entitlement = SubscriptionFreezeEntitlement.builder()
                    .subscriptionId(subscriptionId)
                    .userId(sub.getUserId())
                    .totalDays(totalDays)
                    .consumedDays(sub.getFrozenDaysUsed() != null ? sub.getFrozenDaysUsed() : 0)
                    .reservedDays(0)
                    .policyVersion(tierPolicyProvider.getPolicyVersion())
                    .build();
            log.info("Lazy-provisioned entitlement for subId={}: packageName={}, totalDays={}", subscriptionId, packageName, totalDays);
            return Optional.of(entitlementRepository.save(entitlement));
        });
    }

    // ─── Reserve / Release ───────────────────────────────────────────────────

    /**
     * Reserves days during freeze commit. Acquires pessimistic lock.
     * Throws IllegalStateException if insufficient balance.
     */
    @Transactional
    public SubscriptionFreezeEntitlement reserve(Long subscriptionId, int days) {
        SubscriptionFreezeEntitlement entitlement = entitlementRepository
                .findBySubscriptionIdForUpdate(subscriptionId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException(
                        "error.freeze.no_entitlement"));

        entitlement.reserve(days); // throws if insufficient
        SubscriptionFreezeEntitlement saved = entitlementRepository.save(entitlement);
        log.info("Reserved {} freeze days for subscriptionId={}, available now={}",
                days, subscriptionId, saved.getAvailableDays());
        return saved;
    }

    /**
     * Finalises balance on COMPLETED or ENDED_EARLY.
     * usedDays are moved to consumedDays; returnedDays are released from reservedDays.
     */
    @Transactional
    public void finaliseConsumed(Long subscriptionId, int usedDays, int returnedDays) {
        SubscriptionFreezeEntitlement entitlement = entitlementRepository
                .findBySubscriptionIdForUpdate(subscriptionId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException(
                        "error.freeze.no_entitlement"));

        entitlement.finaliseConsumed(usedDays, returnedDays);
        entitlementRepository.save(entitlement);
        log.info("Finalised entitlement for subscriptionId={}: used={}, returned={}, available={}",
                subscriptionId, usedDays, returnedDays, entitlement.getAvailableDays());
    }

    /**
     * Releases reserved days on TERMINATED freeze (no consumption).
     */
    @Transactional
    public void releaseReserved(Long subscriptionId, int reservedDays) {
        entitlementRepository.findBySubscriptionIdForUpdate(subscriptionId).ifPresent(e -> {
            e.setReservedDays(Math.max(0, e.getReservedDays() - reservedDays));
            entitlementRepository.save(e);
            log.info("Released {} reserved days for subscriptionId={} (TERMINATED)",
                    reservedDays, subscriptionId);
        });
    }

    // ─── Upgrade ─────────────────────────────────────────────────────────────

    /**
     * Adjusts entitlement on subscription upgrade (BR-12).
     * totalDays = new tier policy; consumedDays unchanged; reservedDays unchanged.
     * Should be called AFTER the new subscription row is saved.
     */
    @Transactional
    public void onUpgrade(Subscription newSubscription, String newPackageName) {
        Long subId = newSubscription.getSubscriptionId();
        int newTotal = tierPolicyProvider.getAllowedDays(newPackageName);

        SubscriptionFreezeEntitlement entitlement = entitlementRepository
                .findBySubscriptionIdForUpdate(subId)
                .orElseGet(() -> {
                    // Entitlement may not exist if subscription was created before BRD v1.1
                    log.warn("No entitlement found for subscriptionId={} during upgrade; creating.", subId);
                    return SubscriptionFreezeEntitlement.builder()
                            .subscriptionId(subId)
                            .userId(newSubscription.getUserId())
                            .consumedDays(0)
                            .reservedDays(0)
                            .policyVersion(tierPolicyProvider.getPolicyVersion())
                            .build();
                });

        entitlement.setTotalDays(newTotal);
        entitlement.setPolicyVersion(tierPolicyProvider.getPolicyVersion());
        entitlementRepository.save(entitlement);
        log.info("Upgraded entitlement for subscriptionId={}: newTotalDays={}", subId, newTotal);
    }
}
