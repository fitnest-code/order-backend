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
public class AdminPromoCampaignSummaryResponse {

    private Integer activeCampaigns;
    private Long eligibleNumbers;
    private Long redeemedLast30Days;
    private Long redeemedPrevious30Days;
    private BigDecimal discountGivenLast30Days;
    private BigDecimal discountGivenPrevious30Days;
}