package az.fitnest.order.model.enums;

/**
 * Row filter of the visit history. {@code HOME_GYM} maps to the legacy 1.6 history;
 * {@code STANDARD} covers every counted visit, including the
 * {@code HOME_GYM_PENDING} phase before a home gym is chosen.
 */
public enum VisitHistoryMode {
    ALL,
    HOME_GYM,
    STANDARD
}
