package az.fitnest.order.model.enums;

/**
 * Reason a home-gym row stopped being ACTIVE (spec R13/R18/R5).
 */
public enum HomeGymEndReason {
    SWITCH,
    CHANGE,
    UPGRADE_NOT_IN_PACKAGE,
    GYM_HOME_DISABLED,
    SUBSCRIPTION_ENDED,
    /** Payment refund: the home gym ended because the subscription was refunded. */
    REFUND
}
