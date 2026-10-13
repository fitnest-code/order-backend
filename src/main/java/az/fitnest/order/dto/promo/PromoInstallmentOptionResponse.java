package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PromoInstallmentOptionResponse {

    private Integer months;
    private BigDecimal monthlyAmount;
    private BigDecimal totalAmount;
    private String bank;
}