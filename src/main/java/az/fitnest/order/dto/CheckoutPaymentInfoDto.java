package az.fitnest.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "Payment initiation result from checkout create")
public record CheckoutPaymentInfoDto(
    @Schema(description = "Rail: epoint | abb | bob | apple_pay | google_pay | saved_card", example = "apple_pay")
    String provider,
    @Schema(description = "Epoint transaction id for APPLE_PAY/GOOGLE_PAY (debug). Null for most other rails.")
    String paymentIntentClientSecret,
    @Schema(description = "Open in WebView. For APPLE_PAY/GOOGLE_PAY this is the pre-priced Epoint wallet widget URL "
            + "(BF discounted). For CARD it is the Epoint card widget. Null when SAVED_CARD charged with no 3DS.")
    String paymentUrl,
    @Schema(description = "Initiation status, typically success while the user still completes payment in the widget")
    String status
) {}
