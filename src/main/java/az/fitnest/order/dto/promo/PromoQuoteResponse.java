package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class PromoQuoteResponse {

    @Builder.Default
    private String currency = "AZN";
    private BigDecimal baseAmount;
    private PromoQuotePromoResponse promo;
    private BigDecimal fitCoinAmount;
    private BigDecimal finalAmount;
    private PromoInstallmentsResponse installments;
}