package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
public class AdminPromoCampaignStatsResponse {

    private Long eligibleNumbers;
    private Long registeredUsers;
    private Long redeemedCount;
    private Long popupShown;
    private Long popupSeen;
    private BigDecimal discountGiven;
    private BigDecimal avgDiscountPerOrder;
    private OffsetDateTime updatedAt;
}