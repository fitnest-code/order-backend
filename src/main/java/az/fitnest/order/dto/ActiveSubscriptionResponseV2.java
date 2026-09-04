package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

@Builder
public record ActiveSubscriptionResponseV2(
        @JsonProperty("order_id")
        String orderId,
        String status,
        @JsonProperty("expires_at")
        @JsonFormat(pattern = "dd/MM/yyyy")
        LocalDate expiresAt,
        SubscriptionDetailsDto subscription,
        @JsonProperty("coin_balance")
        BigDecimal coinBalance,
        @JsonProperty("coin_azn_equivalent")
        BigDecimal coinAznEquivalent,
        @JsonProperty("coin_validity_date")
        String coinValidityDate
) {
    public static ActiveSubscriptionResponseV2 from(
            ActiveSubscriptionResponse v1,
            BigDecimal coinBalance,
            BigDecimal coinAznEquivalent,
            String coinValidityDate
    ) {
        return ActiveSubscriptionResponseV2.builder()
                .orderId(v1.order_id())
                .status(v1.status())
                .expiresAt(v1.expires_at())
                .subscription(v1.subscription())
                .coinBalance(coinBalance != null ? coinBalance : BigDecimal.ZERO)
                .coinAznEquivalent(coinAznEquivalent != null ? coinAznEquivalent : BigDecimal.ZERO)
                .coinValidityDate(coinValidityDate)
                .build();
    }
}
