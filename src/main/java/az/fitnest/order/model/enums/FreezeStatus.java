package az.fitnest.order.model.enums;

/**
 * Lifecycle status of a plan-level subscription freeze episode.
 *
 * NOTE: Do NOT confuse with reservation visit-hold states (frozenSessions / freezeSession).
 *
 * Transitions:
 *   ACTIVE → COMPLETED    (worker or lazy-finalize when planEndAt reached)
 *   ACTIVE → ENDED_EARLY  (user resume before planEndAt)
 *   ACTIVE → TERMINATED   (revoke / upgrade / refund cancels membership)
 */
public enum FreezeStatus {

    /** Freeze is in progress; subscription.status = FROZEN. */
    ACTIVE,

    /** planEndAt reached; full requested days consumed; subscription re-activated. */
    COMPLETED,

    /** User resumed early; only elapsed days consumed; subscription re-activated. */
    ENDED_EARLY,

    /** Freeze cancelled due to revoke / upgrade / refund; subscription not re-activated. */
    TERMINATED
}
