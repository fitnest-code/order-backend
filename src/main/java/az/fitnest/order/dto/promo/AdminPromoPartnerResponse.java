package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AdminPromoPartnerResponse {

    private UUID id;
    private String name;
    private String logoUrl;
    private String logoOnDarkUrl;
    private String brandColor;
    private String contactName;
    private String contactEmail;
    private String agreementRef;
    private Integer retentionDays;
    private List<String> defaultPrefixes;
    private String status;
    private Integer activeCampaigns;
    private Integer totalCampaigns;
    private Long eligibleNumbers;
    private Long redeemedCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}