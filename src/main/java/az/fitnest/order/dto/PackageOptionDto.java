package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Builder
public record PackageOptionDto(
    @JsonProperty("option_id")
    Long optionId,
    @JsonProperty("duration_months")
    Integer durationMonths,
    @JsonProperty("duration_label")
    String durationLabel,
    PackagePriceDto price,
    String badge,
    @JsonProperty("visit_limit")
    Integer visitLimit,
    @JsonProperty("freeze_days")
    Integer freezeDays,
    List<PackageBenefitDto> benefits,
    @JsonProperty("bonus_months")
    Integer bonusMonths,
    @JsonProperty("total_months")
    Integer totalMonths,
    @JsonProperty("campaign_id")
    Long campaignId,
    @JsonProperty("campaign_label")
    String campaignLabel,
    @JsonProperty("strike_through_total")
    java.math.BigDecimal strikeThroughTotal,
    @JsonProperty("savings_amount")
    java.math.BigDecimal savingsAmount,
    @JsonProperty("monthly_equivalent")
    java.math.BigDecimal monthlyEquivalent
) {}
