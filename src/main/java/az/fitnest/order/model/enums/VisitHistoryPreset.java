package az.fitnest.order.model.enums;

/**
 * Quick date ranges of {@code GET /api/v1/me/visits/history}.
 *
 * <p>All ranges are resolved on the server in Asia/Baku and returned to the client as
 * real dates, so the mobile app never has to do calendar arithmetic itself.
 */
public enum VisitHistoryPreset {
    LAST_7_DAYS,
    LAST_30_DAYS,
    PREVIOUS_MONTH,
    CURRENT_SUBSCRIPTION,
    ARCHIVE,
    CUSTOM
}
