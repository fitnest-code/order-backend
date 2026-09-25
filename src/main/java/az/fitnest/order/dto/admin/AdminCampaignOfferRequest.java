package az.fitnest.order.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminCampaignOfferRequest {

    @NotNull
    @JsonProperty("base_duration_months")
    private Integer baseDurationMonths;

    @NotNull
    @JsonProperty("bonus_months")
    private Integer bonusMonths;

    private String label;
}
