package az.fitnest.order.dto.homegym;

import java.util.List;

/**
 * Response of {@code GET /api/v1/home-gym/rules} - the "Əsas zal qaydaları" block of the
 * mobile app (screens 1.1 and 1.4).
 *
 * <p>The text is fully server-driven: the client only renders {@code title},
 * {@code toggleLabel} and {@code items} in the given order. {@code limits} repeats the
 * configuration values that were substituted into the texts so the client can show
 * counters without hard-coding numbers.
 */
public record HomeGymRulesResponse(
        String title,
        String toggleLabel,
        Limits limits,
        List<Item> items) {

    /** The four configuration values used by the QR decision logic and by the texts. */
    public record Limits(
            int homeGymMonthlyLimit,
            int homeGymDailyLimit,
            int standardMonthlyLimit,
            int otherGymsLockThreshold) {
    }

    /** One rule row; {@code key} is stable and safe for analytics/ordering. */
    public record Item(String key, String title, String text) {
    }
}
