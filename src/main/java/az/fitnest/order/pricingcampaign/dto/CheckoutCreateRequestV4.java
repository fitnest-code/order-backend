package az.fitnest.order.pricingcampaign.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * v4 checkout create. New contract for Kapital Bank; v3 stays frozen (Epoint / TamKart / BolKart only).
 */
@Schema(description = "BF /api/v4/checkout create body (adds KAPITAL)")
public record CheckoutCreateRequestV4(
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

        /** Epoint CARD installment widget flag only (0/1). Bank installments use installmentProvider+months. */
        @Schema(description = "Epoint CARD widget installment flag only (0/1). Ignored for bank rails/wallets.",
                example = "0")
        Integer is_installment,

        /** Total the user saw; mismatch yields 409 error.price_changed. */
        @Schema(description = "Must equal preview.total or API returns 409 price_changed", example = "29.99")
        BigDecimal expectedFinalAmount,

        /**
         * CARD (default) | KAPITAL | INSTALLMENT | APPLE_PAY | GOOGLE_PAY | SAVED_CARD.
         * KAPITAL = Kapital Bank card HPP (no Taksit).
         */
        @JsonAlias("payment_type")
        @Schema(description = "Payment rail",
                allowableValues = {"CARD", "KAPITAL", "INSTALLMENT", "APPLE_PAY", "GOOGLE_PAY", "SAVED_CARD"},
                example = "KAPITAL")
        String paymentType,

        /** TAMKART | BOLKART | KAPITAL; required when paymentType=INSTALLMENT. */
        @JsonAlias("installment_provider")
        @Schema(description = "Required when paymentType=INSTALLMENT",
                allowableValues = {"TAMKART", "BOLKART", "KAPITAL"})
        String installmentProvider,

        /** Installment term in months; required when paymentType=INSTALLMENT (KAPITAL: 3|6|12). */
        @JsonAlias("installment_months")
        @Schema(description = "Required when paymentType=INSTALLMENT", example = "3")
        Integer installmentMonths,

        /** Epoint saved-card id; required when paymentType=SAVED_CARD. */
        @JsonAlias("card_id")
        @Schema(description = "Required when paymentType=SAVED_CARD (Epoint saved card id)")
        String cardId) {}
