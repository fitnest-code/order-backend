package az.fitnest.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignOfferDto {
    private Integer baseDurationMonths;
    private Integer bonusMonths;
    private Integer totalMonths;
    private String label;
}
