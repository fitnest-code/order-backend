package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record AdminAssignSubscriptionRequest(
    @NotNull(message = "error.missing_field")
    @JsonProperty("user_id")
    Long userId,

    @NotNull(message = "error.missing_field")
    @JsonProperty("plan_id")
    Long planId,

    @NotNull(message = "error.missing_field")
    @JsonProperty("option_id")
    Long optionId,

    @JsonProperty("auto_payment_enabled")
    Boolean autoPaymentEnabled,

    @JsonProperty("apply_campaign")
    Boolean applyCampaign
) {}
