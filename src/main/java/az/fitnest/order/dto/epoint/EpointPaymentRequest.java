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
    Double coinsUsed
) {}
