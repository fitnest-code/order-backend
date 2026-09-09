package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
@Schema(description = "Abunəlik paketinin yığcam v3 cavabı")
public record SubscriptionPackageSummaryV3(
        @JsonProperty("subscription_name")
        @Schema(description = "Paket adı (Bronze, Silver, Gold, Platinum)", example = "Gold")
        String subscriptionName,

        @JsonProperty("gym_count")
        @Schema(description = "Bu paketi dəstəkləyən aktiv idman zallarının sayı", example = "42")
        long gymCount,

        @JsonProperty("monthly_price")
        @Schema(description = "Ən qısa müddətli (aylıq) paket variantının qiyməti", example = "49.00")
        BigDecimal monthlyPrice,

        @Schema(description = "Bu paketin unikal xidmətləri / faydaları")
        List<String> services
) {
}
