package az.fitnest.order.pricingcampaign.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CheckoutCreateRequestV2(
        @NotNull @JsonAlias("package_id") Long packageId,
        @NotNull @JsonAlias("option_id") Long optionId,
        Boolean useFitCoin,
        Integer is_installment,
        /** Total the user saw; mismatch yields 409 error.price_changed. */
        BigDecimal expectedFinalAmount) {}
