package az.fitnest.order.model.enums;

/**
 * Origin of the home‑gym selection.
 */
public enum HomeGymSource {
    POST_PAYMENT,
    SUBSCRIPTION_SCREEN,
    FIRST_QR,
    /** R19: the only gym visited in the current subscription month. */
    SUGGESTED,
    ADMIN
}
