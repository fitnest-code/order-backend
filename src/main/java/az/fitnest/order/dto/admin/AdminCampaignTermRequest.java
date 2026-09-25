package az.fitnest.order.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminCampaignTermRequest {

    @NotNull
    @JsonProperty("sort_order")
    private Integer sortOrder;

    @NotNull
    @JsonProperty("is_positive")
    private Boolean isPositive;

    private String text;
}
