package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class PromoInstallmentsResponse {

    private List<PromoInstallmentOptionResponse> options;
    private BigDecimal monthlyAmount;
    private Integer months;
}