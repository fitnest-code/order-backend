package az.fitnest.order.dto.epoint;

import lombok.Builder;
import java.util.List;

@Builder
public record EpointPaymentRequest(
    String language,
    String order_id,
    Double amount,
    String currency,
    String description,
    Integer is_installment,
    Integer refund,
    List<Object> other_attr,
    Boolean prePriced,
    Long userId,
    /** Coin units already subtracted from amount; payment-backend spends them on SUCCESS. */
    Double coinsUsed,
    /** epoint | abb | bob; null/empty means epoint (payment-backend default). */
    String provider,
    /** TamKart/BolKart term in months; ignored by Epoint. */
    Integer installmentMonths,
    /** Epoint saved-card id; only sent with provider=saved_card. */
    String cardId
) {}
