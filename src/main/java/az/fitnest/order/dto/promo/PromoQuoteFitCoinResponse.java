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
public class PromoQuoteFitCoinResponse {

    private BigDecimal balanceCoins;
    private BigDecimal balanceAmount;
    private BigDecimal usedCoins;
    private BigDecimal usedAmount;
}
