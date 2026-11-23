package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDate;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Aktiv abunəliyin yığcam v4 cavabı (v3 + ödəniş raili)")
public record ActiveSubscriptionResponseV4(
        @JsonProperty("subscription_name")
        String subscriptionName,

        @JsonProperty("plan_duration_months")
        Integer planDurationMonths,

        @JsonProperty("status")
        String status,

        @JsonProperty("next_payment_due_at")
        @JsonFormat(pattern = "yyyy-MM-dd")
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
        CampaignConfirmationBannerDto campaignConfirmationBanner,

        @JsonProperty("payment_provider")
        @Schema(description = "Son satınalma ödəniş provayderi", example = "ABB_BNPL")
        String paymentProvider,

        @JsonProperty("bnpl_order_number")
        @Schema(description = "ABB BNPL sifariş identifikatoru")
        String bnplOrderNumber,

        @JsonProperty("bnpl_term")
        @Schema(description = "ABB BNPL kredit müddəti (ay)")
        Integer bnplTerm,

        @JsonProperty("auto_renew_enabled")
        @Schema(description = "Avtomatik yenilənmə aktivdir")
        Boolean autoRenewEnabled
) {
    public static ActiveSubscriptionResponseV4 none() {
        return ActiveSubscriptionResponseV4.builder()
                .status("NONE")
                .build();
    }
}