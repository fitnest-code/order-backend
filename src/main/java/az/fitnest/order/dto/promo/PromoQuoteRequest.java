package az.fitnest.order.dto.promo;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PromoQuoteRequest {

    @NotNull(message = "Plan ID is required")
    private String planId;

    private String promoCode;

    @Builder.Default
    private Boolean useFitCoin = false;

    @NotNull(message = "Payment type is required")
    private String paymentType; // CARD, APPLE_PAY, GOOGLE_PAY, INSTALLMENT

    private BigDecimal basePrice;
}