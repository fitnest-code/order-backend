package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromoCampaignStatsResponse {

    private Long eligibleNumbers;
    private Long registeredUsers;
    private Long redeemedCount;
    private Long popupShown;
    private Long popupSeen;
    private BigDecimal discountGiven;
    private BigDecimal avgDiscountPerOrder;
    private OffsetDateTime updatedAt;
    private AdminPromoCampaignFunnelResponse funnel;
}