package az.fitnest.order.dto.admin;

import az.fitnest.order.model.entity.CampaignStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminCampaignRequest {

    @NotBlank(message = "code is required")
    private String code;

    @NotNull(message = "status is required")
    private CampaignStatus status;

    @NotNull(message = "startAt is required")
    @JsonProperty("start_at")
    private LocalDateTime startAt;

    @NotNull(message = "endAt is required")
    @JsonProperty("end_at")
    private LocalDateTime endAt;

    @JsonProperty("banner_image_url")
    private String bannerImageUrl;

    @JsonProperty("cta_target")
    private String ctaTarget = "CAMPAIGN_DETAIL";

    @JsonProperty("one_per_user")
    private Boolean onePerUser = true;

    private String title;
    private String description;

    @JsonProperty("cta_label")
    private String ctaLabel;
}
