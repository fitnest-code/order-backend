package az.fitnest.order.dto.promo;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class PromoQuoteRequest {

    @NotNull(message = "Plan ID is required")
    private UUID planId;

    private String promoCode;

    @Builder.Default
    private Boolean useFitCoin = false;

    @NotNull(message = "Payment type is required")
    private String paymentType; // CARD, APPLE_PAY, GOOGLE_PAY, INSTALLMENT
}