package az.fitnest.order.pricingcampaign.controller;

import az.fitnest.order.pricingcampaign.dto.*;
import az.fitnest.order.pricingcampaign.service.PricingCheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * v3 checkout: multi-rail (Epoint/ABB/BOB). Preview pricing is identical to v2, so the v2 preview DTOs are
 * reused; only create gets a new request contract. v2 stays Epoint-only for backward compatibility.
 */
@RestController
@RequestMapping("/api/v3/checkout")
@RequiredArgsConstructor
@Tag(name = "Checkout v3 (Black Friday)", description = "Multi-rail BF checkout: Epoint CARD, TamKart/BolKart, "
        + "Apple Pay / Google Pay (Epoint widget paymentUrl), Epoint saved card. Use api-dev for BF testing.")
public class CheckoutControllerV3 {

    private final PricingCheckoutService service;

    @PostMapping("/preview")
    @Operation(summary = "BF price preview (same pricing as v2)",
            description = "Returns list vs discounted total. Create with expectedFinalAmount = preview.total.")
    public CheckoutPreviewResponseV2 preview(@Valid @RequestBody CheckoutPreviewRequestV2 request) {
        return service.preview(SubscriptionHomeStateController.requireUser(), request);
    }

    @PostMapping
    @Operation(summary = "BF create order + start payment",
            description = """
                    paymentType:
                    - CARD (default) → Epoint widget URL in payment.paymentUrl
                    - INSTALLMENT + installmentProvider TAMKART|BOLKART + installmentMonths → bank redirect URL
                    - APPLE_PAY → pre-priced Epoint Apple Pay widget URL in payment.paymentUrl (open in WKWebView; do not call epoint/widget-url)
                    - GOOGLE_PAY → same as Apple Pay for Android WebView
                    - SAVED_CARD + cardId → charge saved Epoint card; paymentUrl null or 3DS redirect
                    Poll GET /api/v2/me/payments/order/{orderId} after opening paymentUrl.
                    """)
    public CheckoutCreateResponseV2 create(@Valid @RequestBody CheckoutCreateRequestV3 request) {
        return service.createV3(SubscriptionHomeStateController.requireUser(), request);
    }
}
