package az.fitnest.order.service.freeze;

import org.springframework.stereotype.Component;

/**
 * Maps subscription tier (derived from SubscriptionPackage.name) to
 * the number of freeze days a subscriber is entitled to.
 *
 * Source of truth: BRD v1.1 §5 (Tier Policy Table).
 * Do NOT add a freeze_days column to PackageOption — tier policy is the authority.
 *
 * Tier detection: case-insensitive substring match on package name.
 * If no tier matches (e.g. trial, gift), returns 0 — caller must decide
 * whether to create an entitlement (BA decision, see freeze.txt §9).
 */
@Component
public class FreezeTierPolicyProvider {

    private static final String POLICY_VERSION = "v1.1";

    /**
     * Returns allowed freeze days for the given package name.
     *
     * Bronze  → 2 days
     * Silver  → 3 days
     * Gold    → 5 days
     * Platinum → 7 days
     * Unknown / trial / free → 0 days
     */
    public int getAllowedDays(String packageName) {
        if (packageName == null) return 0;
        String lower = packageName.toLowerCase();
        if (lower.contains("platinum")) return 7;
        if (lower.contains("gold"))     return 5;
        if (lower.contains("silver"))   return 3;
        if (lower.contains("bronze"))   return 2;
        return 0;
    }

    /**
     * Current policy version — stored on entitlement for audit / future migrations.
     */
    public String getPolicyVersion() {
        return POLICY_VERSION;
    }

    /**
     * Returns true if the package qualifies for a freeze entitlement.
     * Used by assignSubscriptionToUser to skip trial/free plans.
     */
    public boolean qualifiesForEntitlement(String packageName) {
        return getAllowedDays(packageName) > 0;
    }
}
