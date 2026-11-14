package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromoAudienceUploadStatusResponse {

    private UUID id;
    private String status;
    private Integer totalRows;
    private Integer valid;
    private Integer duplicates;
    private Integer invalid;
    private Integer existingUsers;
    private String reportUrl;
    private List<AdminPromoAudienceProblemRow> problemRows;
}