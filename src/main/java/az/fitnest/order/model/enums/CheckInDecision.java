package az.fitnest.order.model.enums;

/**
 * Result of the QR check-in decision engine (spec §"QR giriş qərar məntiqi").
 */
public enum CheckInDecision {
    ENTERED_HOME_UNLIMITED,
    ENTERED_STANDARD,
    ENTERED_OFFER_HOME_GYM,
    CONFIRMATION_REQUIRED,
    OTHER_GYMS_LOCKED,
    VISIT_LIMIT_EXCEEDED,
    TIER_NOT_ALLOWED_AT_THIS_TIME,
    SUBSCRIPTION_FROZEN,
    NO_ACTIVE_SUBSCRIPTION
}
