package az.fitnest.order.dto.homegym;

import java.math.BigDecimal;

/**
 * Check-in request as resolved by catalog: time-slot rule and price snapshot (R14/R15).
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
        BigDecimal unlimitedPrice) {
}
