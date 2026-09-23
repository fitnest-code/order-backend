package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SubscriptionDetailsDto {
    @JsonProperty("subscription_id")
    private Long subscriptionId;

    @JsonProperty("package_id")
    private String packageId;

    @JsonProperty("package_name")
    private String packageName;

    @JsonProperty("duration_months")
    private Integer durationMonths;

    @JsonProperty("duration_label")
    private String durationLabel;

    @JsonProperty("effective_price")
    private BigDecimal effectivePrice;

    private String currency;

    @JsonProperty("total_limit")
    private Integer totalLimit;

    @JsonProperty("remaining_limit")
    private Integer remainingLimit;

    @JsonProperty("start_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy")
    private LocalDate startAt;

    @JsonProperty("end_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy")
    private LocalDate endAt;

    @JsonProperty("frozen_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy")
    private LocalDate frozenAt;

    @JsonProperty("unfreezes_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy")
    private LocalDate unfreezesAt;

    @JsonProperty("frozen_days_used")
    private Integer frozenDaysUsed;

    @JsonProperty("allowed_freeze_days")
    private Integer allowedFreezeDays;

    @JsonProperty("remaining_freeze_days")
    private Integer remainingFreezeDays;

    @JsonProperty("total_freeze_days")
    private Integer totalFreezeDays;

    @JsonProperty("consumed_freeze_days")
    private Integer consumedFreezeDays;

    @JsonProperty("reserved_freeze_days")
    private Integer reservedFreezeDays;

    @JsonProperty("available_freeze_days")
    private Integer availableFreezeDays;

    @JsonProperty("can_freeze")
    private Boolean canFreeze;

    @JsonProperty("raw_status")
    private String rawStatus;

    @JsonProperty("option_id")
    private Long optionId;

    @JsonProperty("benefits")
    private List<PackageBenefitDto> benefits;

    @JsonProperty("automatic_payment_enabled")
    private Boolean automaticPaymentEnabled;
}
