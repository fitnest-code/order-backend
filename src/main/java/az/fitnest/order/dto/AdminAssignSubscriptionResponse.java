package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AdminAssignSubscriptionResponse(
    @JsonProperty("subscription_id")
    Long subscriptionId,

    @JsonProperty("user_id")
    Long userId,

    @JsonProperty("plan_id")
    Long planId,

    @JsonProperty("plan_name")
    String planName,

    @JsonProperty("option_id")
    Long optionId,

    @JsonProperty("duration_months")
    Integer durationMonths,

    String status,

    @JsonProperty("start_at")
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy")
    java.time.LocalDate startAt,

    @JsonProperty("end_at")
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy")
    java.time.LocalDate endAt,

    @JsonProperty("total_limit")
    Integer totalLimit,

    @JsonProperty("remaining_limit")
    Integer remainingLimit,

    @JsonProperty("allowed_freeze_days")
    Integer allowedFreezeDays,

    @JsonProperty("bonus_months")
    Integer bonusMonths,

    @JsonProperty("campaign_applied")
    Boolean campaignApplied,

    @JsonProperty("campaign_id")
    Long campaignId,

    String message
) {}
