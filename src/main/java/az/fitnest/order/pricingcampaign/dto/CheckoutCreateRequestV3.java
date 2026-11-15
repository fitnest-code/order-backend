package az.fitnest.order.pricingcampaign.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * v3 checkout create. Separate from V2 because the product rule forbids changing published DTOs in place;
 * V2 stays Epoint-only, V3 adds the multi-rail fields (Epoint / ABB TamKart / BOB BolKart).
 */
public record CheckoutCreateRequestV3(
        @NotNull @JsonAlias("package_id") Long packageId,
        @NotNull @JsonAlias("option_id") Long optionId,
        Boolean useFitCoin,
        /** Epoint CARD installment widget flag only (0/1). TamKart/BolKart use installmentProvider+months. */
        Integer is_installment,
        /** Total the user saw; mismatch yields 409 error.price_changed. */
        BigDecimal expectedFinalAmount,
        /** CARD (default) | INSTALLMENT | APPLE_PAY | GOOGLE_PAY | SAVED_CARD (Epoint saved card; needs cardId). */
        @JsonAlias("payment_type") String paymentType,
        /** TAMKART (ABB) | BOLKART (BOB); required when paymentType=INSTALLMENT. */
        @JsonAlias("installment_provider") String installmentProvider,
        /** Installment term in months; required when paymentType=INSTALLMENT. */
        @JsonAlias("installment_months") Integer installmentMonths,
        /** Epoint saved-card id from the card list; required when paymentType=SAVED_CARD, ignored otherwise. */
        @JsonAlias("card_id") String cardId) {}
