package az.fitnest.order.service.freeze;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Maps subscription tier (derived from SubscriptionPackage.name) to
 * the number of freeze days a subscriber is entitled to.
 *
 * Source of truth: BRD v1.2 §5 (Tier Policy Table).
 * Do NOT add a freeze_days column to PackageOption — tier policy is the authority.
 *
 * Tier detection: case-insensitive substring match on package name.
 * If no tier matches (e.g. trial, gift), returns 0 — caller must decide
 * whether to create an entitlement (BA decision, see freeze.txt §9).
 */
@Component
public class FreezeTierPolicyProvider {

    public static final int BRONZE_DAYS = 5;
    public static final int SILVER_DAYS = 7;
    public static final int GOLD_DAYS = 10;
    public static final int PLATINUM_DAYS = 12;

    private static final String POLICY_VERSION = "v1.2";

    /**
     * Returns allowed freeze days for the given package name.
     *
     * Bronze  → 5 days
     * Silver  → 7 days
     * Gold    → 10 days
     * Platinum → 12 days
     * Unknown / trial / free → 0 days
     */
    public int getAllowedDays(String packageName) {
        if (packageName == null) return 0;
        String lower = packageName.toLowerCase(Locale.ROOT);
        if (lower.contains("platinum")) return PLATINUM_DAYS;
        if (lower.contains("gold"))     return GOLD_DAYS;
        if (lower.contains("silver"))   return SILVER_DAYS;
        if (lower.contains("bronze"))   return BRONZE_DAYS;
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
