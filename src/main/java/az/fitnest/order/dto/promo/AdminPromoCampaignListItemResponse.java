package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromoCampaignListItemResponse {

    private UUID id;
    private String name;
    private String code;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private String audienceType;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private Integer redeemedCount;
    private Integer totalLimit;
    private String status;
    private Integer priority;
    private String partnerName;
    private OffsetDateTime createdAt;
    private UUID partnerId;
    private Long eligibleNumbers;
    private BigDecimal discountGivenLast30Days;
}