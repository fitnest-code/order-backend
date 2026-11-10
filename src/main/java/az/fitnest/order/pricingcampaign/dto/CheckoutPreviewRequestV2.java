package az.fitnest.order.pricingcampaign.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

public record CheckoutPreviewRequestV2(
        @NotNull @JsonAlias("package_id") Long packageId,
        @NotNull @JsonAlias("option_id") Long optionId,
        Boolean useFitCoin) {}
