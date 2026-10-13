package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class AdminPromoAudienceUploadResponse {

    private UUID id;
    private UUID campaignId;
    private String fileName;
    private String mode;
    private Integer totalRows;
    private Integer valid;
    private Integer duplicates;
    private Integer invalid;
    private Integer existingUsers;
    private String status;
    private String reportUrl;
    private Long uploadedBy;
    private OffsetDateTime uploadedAt;
}