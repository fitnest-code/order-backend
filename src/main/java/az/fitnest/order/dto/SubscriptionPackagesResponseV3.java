package az.fitnest.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Builder
@Schema(description = "Bütün aktiv abunəlik paketlərinin yığcam v3 siyahısı")
public record SubscriptionPackagesResponseV3(
        List<SubscriptionPackageSummaryV3> items
) {
}
