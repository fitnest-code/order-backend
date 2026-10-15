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
public class PromoInstallmentOptionResponse {

    private Integer months;
    private BigDecimal monthlyAmount;
    private BigDecimal totalAmount;
    private String bank;
}