package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromoQuoteResponse {

    @Builder.Default
    private String currency = "AZN";
    private String packageId;
    private Long optionId;
    private BigDecimal baseAmount;
    private PromoQuotePromoResponse promo;
    private BigDecimal fitCoinAmount;
    private PromoQuoteFitCoinResponse fitCoin;
    private BigDecimal finalAmount;
    private PromoInstallmentsResponse installments;
}
