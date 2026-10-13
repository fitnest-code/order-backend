package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminPromoCodeAvailabilityResponse {

    private Boolean available;
    private String message;
}