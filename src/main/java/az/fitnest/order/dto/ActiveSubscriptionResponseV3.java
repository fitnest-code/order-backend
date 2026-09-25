package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDate;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Aktiv abunəliyin yığcam v3 cavabı")
public record ActiveSubscriptionResponseV3(
        @JsonProperty("subscription_name")
        @Schema(description = "Paket adı (Bronze, Silver, Gold, Platinum)", example = "Bronze")
        String subscriptionName,

        @JsonProperty("plan_duration_months")
        @Schema(description = "Plan müddəti aylarla (1, 3, 6, 12). Müştəri tərəfində tərcümə olunur.", example = "3")
        Integer planDurationMonths,

        @JsonProperty("status")
        @Schema(description = "Abunəlik statusu", example = "active")
        String status,

        @JsonProperty("next_payment_due_at")
        @JsonFormat(pattern = "yyyy-MM-dd")
        @Schema(description = "Növbəti ödəniş tarixi (abunəliyin bitmə/yenilənmə günü)", example = "2026-10-09")
        LocalDate nextPaymentDueAt,

        @JsonProperty("paid_duration_months")
        Integer paidDurationMonths,

        @JsonProperty("bonus_months")
        Integer bonusMonths,

        @JsonProperty("total_months")
        Integer totalMonths,

        @JsonProperty("campaign_label")
        String campaignLabel,

        @JsonProperty("service_end_at")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate serviceEndAt,

        @JsonProperty("campaign_confirmation_banner")
        CampaignConfirmationBannerDto campaignConfirmationBanner
) {
    public static ActiveSubscriptionResponseV3 none() {
        return ActiveSubscriptionResponseV3.builder()
                .status("none")
                .build();
    }
}
