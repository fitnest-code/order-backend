package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromoErrorDetail {

    private String code;
    private String message;
}