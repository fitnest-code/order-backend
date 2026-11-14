package az.fitnest.order.model.enums;

/**
 * How a gym visit counts against the monthly 12-visit limit.
 */
public enum VisitEntryType {
    /** Home-gym visit: unlimited while the home gym is active (R3). */
    HOME_UNLIMITED,
    /** Regular visit counted against the monthly limit (R7). */
    STANDARD
}
