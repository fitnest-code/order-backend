package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromoAudienceProblemRow {

    private Integer rowNumber;
    private String phone;
    private String issue; // DUPLICATE_IN_FILE, DUPLICATE_IN_CAMPAIGN, INVALID_FORMAT, INVALID_PREFIX, NOT_MOBILE
}