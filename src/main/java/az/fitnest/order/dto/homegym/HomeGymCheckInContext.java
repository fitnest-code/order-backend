package az.fitnest.order.dto.homegym;

import java.math.BigDecimal;

/**
 * Check-in request as resolved by catalog: time-slot rule and price snapshot (R14/R15).
 *
 * <p>{@code gymName} is a snapshot supplied by catalog; it is stored on the visit and on
 * rejected attempts so later gym renames do not rewrite history.
 */
public record HomeGymCheckInContext(
        Long userId,
        Long gymId,
        boolean consumeFrozen,
        String confirmation,
        String confirmationToken,
        Long timeSlotRuleId,
        String pricePackage,
        BigDecimal singlePrice,
        BigDecimal unlimitedPrice,
        String qrScanId,
        String gymName) {

    /** Backwards-compatible constructor for callers that do not carry a gym name. */
    public HomeGymCheckInContext(Long userId,
                                 Long gymId,
                                 boolean consumeFrozen,
                                 String confirmation,
                                 String confirmationToken,
                                 Long timeSlotRuleId,
                                 String pricePackage,
                                 BigDecimal singlePrice,
                                 BigDecimal unlimitedPrice,
                                 String qrScanId) {
        this(userId, gymId, consumeFrozen, confirmation, confirmationToken, timeSlotRuleId,
                pricePackage, singlePrice, unlimitedPrice, qrScanId, null);
    }
}
