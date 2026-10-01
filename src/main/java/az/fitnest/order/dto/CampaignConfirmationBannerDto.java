package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignConfirmationBannerDto {
    @JsonProperty("campaign_id")
    private Long campaignId;
    @JsonProperty("bonus_months")
    private Integer bonusMonths;
    private String title;
    private String body;
}
