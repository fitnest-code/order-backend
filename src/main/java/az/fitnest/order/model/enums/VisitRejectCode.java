package az.fitnest.order.model.enums;

/**
 * Stable rejection codes stored in {@code visit_attempt} and returned in the history.
 *
 * <p>Order records its own gates (limit, daily, other-gyms, frozen, expired). Catalog
 * records the gates it evaluates before calling order and publishes
 * {@code VISIT_ATTEMPT_REJECTED} for them.
 */
public enum VisitRejectCode {
    STANDARD_LIMIT_REACHED,
    HOME_MONTHLY_LIMIT_REACHED,
    DAILY_LIMIT_REACHED,
    OTHER_GYMS_LOCKED,
    TIER_NOT_ALLOWED_AT_THIS_TIME,
    GYM_NOT_IN_PACKAGE,
    SUBSCRIPTION_FROZEN,
    SUBSCRIPTION_EXPIRED
}
