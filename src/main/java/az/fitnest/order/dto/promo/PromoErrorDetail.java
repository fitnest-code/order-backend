package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PromoErrorDetail {

    private String code;
    private String message;
}