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
    /** R21: the home gym already had its one visit of the Baku calendar day. */
    DAILY_LIMIT_REACHED,
    /** R21: the home gym already had its 30 visits of the subscription month. */
    HOME_MONTHLY_LIMIT_REACHED,
    TIER_NOT_ALLOWED_AT_THIS_TIME,
    SUBSCRIPTION_FROZEN,
    NO_ACTIVE_SUBSCRIPTION,
    /** T-16: the same qrScanId was already used for a different gym. */
    QR_SCAN_CONFLICT,
    /** T-16: qrScanId is not a canonical UUID (mobile sends a UUID v4). */
    INVALID_QR_SCAN_ID
}
