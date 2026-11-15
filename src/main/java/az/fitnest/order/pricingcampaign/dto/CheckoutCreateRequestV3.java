package az.fitnest.order.pricingcampaign.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * v3 checkout create. Separate from V2 because the product rule forbids changing published DTOs in place;
 * V2 stays Epoint-only, V3 adds the multi-rail fields (Epoint / ABB TamKart / BOB BolKart).
 */
@Schema(description = "BF /api/v3/checkout create body")
public record CheckoutCreateRequestV3(
        @NotNull
        @JsonAlias("package_id")
        @Schema(description = "Subscription package id", example = "4", requiredMode = Schema.RequiredMode.REQUIRED)
        Long packageId,

        @NotNull
        @JsonAlias("option_id")
        @Schema(description = "Package option id", example = "102", requiredMode = Schema.RequiredMode.REQUIRED)
        Long optionId,

        @Schema(description = "Apply FitCoin to the payable total", example = "false")
        Boolean useFitCoin,

        /** Epoint CARD installment widget flag only (0/1). TamKart/BolKart use installmentProvider+months. */
        @Schema(description = "Epoint CARD widget installment flag only (0/1). Ignored for TamKart/BolKart/wallets.",
                example = "0")
        Integer is_installment,

        /** Total the user saw; mismatch yields 409 error.price_changed. */
        @Schema(description = "Must equal preview.total or API returns 409 price_changed", example = "29.99")
        BigDecimal expectedFinalAmount,

        /** CARD (default) | INSTALLMENT | APPLE_PAY | GOOGLE_PAY | SAVED_CARD (Epoint saved card; needs cardId). */
        @JsonAlias("payment_type")
        @Schema(description = "Payment rail",
                allowableValues = {"CARD", "INSTALLMENT", "APPLE_PAY", "GOOGLE_PAY", "SAVED_CARD"},
                example = "APPLE_PAY")
        String paymentType,

        /** TAMKART (ABB) | BOLKART (BOB); required when paymentType=INSTALLMENT. */
        @JsonAlias("installment_provider")
        @Schema(description = "Required when paymentType=INSTALLMENT", allowableValues = {"TAMKART", "BOLKART"})
        String installmentProvider,

        /** Installment term in months; required when paymentType=INSTALLMENT. */
        @JsonAlias("installment_months")
        @Schema(description = "Required when paymentType=INSTALLMENT", example = "3")
        Integer installmentMonths,

        /** Epoint saved-card id from the card list; required when paymentType=SAVED_CARD, ignored otherwise. */
        @JsonAlias("card_id")
        @Schema(description = "Required when paymentType=SAVED_CARD (Epoint saved card id)")
        String cardId) {}
