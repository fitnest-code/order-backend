package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PromoQuoteFitCoinResponse {

    private BigDecimal balanceCoins;
    private BigDecimal balanceAmount;
    private BigDecimal usedCoins;
    private BigDecimal usedAmount;
}
