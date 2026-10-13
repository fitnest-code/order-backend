package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AdminPromoCampaignResponse {
    private UUID id;
    private UUID partnerId;
    private String partnerName;
    private String name;
    private String code;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private String audienceType;
    private List<String> allowedPrefixes;
    private List<String> planIds;
    private List<String> tierCodes;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private Integer usesPerUser;
    private Integer totalLimit;
    private Integer redeemedCount;
    private Integer priority;
    private Boolean firstPaymentOnly;
    private Boolean allowFitcoin;
    private Boolean allowInstallments;
    private Boolean includeExistingUsers;
    private String brandColorOverride;
    private String logoUrlOverride;
    private String status;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer version;
    private List<AdminPromoCampaignTranslationResponse> translations;
    private AdminPromoCampaignPopupResponse popup;
}