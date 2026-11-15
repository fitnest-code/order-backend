package az.fitnest.order.pricingcampaign.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Ready-to-bind Black Friday intro screen: every user-visible string is already rendered server-side. */
@Schema(description = "Black Friday landing screen payload. All copy is localized and placeholder-free; "
        + "mobile binds it as-is. Null body (HTTP 200) when no campaign is active.")
public record CampaignLandingDto(
        UUID campaignId,
        String code,
        @Schema(description = "Resolved copy locale: az | en | ru", example = "az") String locale,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer maxPercent,
        @Schema(description = "false when the user is on the BAKCELL20 exclusive deal (BF is not payable)")
        boolean eligible,
        @Schema(description = "true after a BF purchase; checkout then charges normal prices")
        boolean usedByUser,
        Header header,
        @Schema(description = "One row per discounted duration (3/6/12), each listing ALL packages offering it")
        List<Option> options,
        @Schema(description = "Terms bullets with campaign dates already substituted") List<String> terms,
        Cta cta) {

    public record Header(
            @Schema(example = "Qara Cümə") String title,
            @Schema(example = "30%-dək endirim!") String headline,
            @Schema(example = "3, 6 və 12 aylıq abunəlikləri Qara Cümə qiyməti ilə al.") String subtitle) {}

    public record Option(
            int months,
            BigDecimal percent,
            @Schema(example = "3 aylıq al - 20 % endirim") String label,
            List<PackagePrice> packages) {}

    public record PackagePrice(
            Long packageId,
            Long optionId,
            @Schema(description = "Localized package name", example = "Platinum") String name,
            BigDecimal listPrice,
            BigDecimal finalPrice,
            BigDecimal savedAmount,
            String currency,
            BigDecimal discountPercent,
            boolean campaignApplied) {}

    public record Cta(@Schema(example = "Planını seç") String title) {}
}
