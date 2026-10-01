package az.fitnest.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveCampaignResponseDto {
    private boolean eligible;
    private boolean showPopup;
    private LocalDateTime nextEligibleShowAt;
    private CampaignDetailDto campaign;
}
