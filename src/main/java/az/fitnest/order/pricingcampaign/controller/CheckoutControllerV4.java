package az.fitnest.order.pricingcampaign.controller;

import az.fitnest.order.pricingcampaign.dto.CheckoutCreateRequestV4;
import az.fitnest.order.pricingcampaign.dto.CheckoutCreateResponseV2;
import az.fitnest.order.pricingcampaign.dto.CheckoutPreviewRequestV2;
import az.fitnest.order.pricingcampaign.dto.CheckoutPreviewResponseV2;
import az.fitnest.order.pricingcampaign.service.PricingCheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * v4 checkout: v3 rails plus Kapital Bank. v2/v3 controllers stay unchanged for old clients.
 */
@RestController
@RequestMapping("/api/v4/checkout")
@RequiredArgsConstructor
@Tag(name = "Checkout v4 (Kapital)", description = "Multi-rail BF checkout including Kapital Bank HPP "
        + "(card + Taksit 3/6/12). Preview pricing matches v2/v3.")
public class CheckoutControllerV4 {

    private final PricingCheckoutService service;

    @PostMapping("/preview")
    @Operation(summary = "BF price preview (same pricing as v2/v3)",
            description = "Returns list vs discounted total. Create with expectedFinalAmount = preview.total.")
    public CheckoutPreviewResponseV2 preview(@Valid @RequestBody CheckoutPreviewRequestV2 request) {
        return service.preview(SubscriptionHomeStateController.requireUser(), request);
    }

    @PostMapping
    @Operation(summary = "BF create order + start payment (v4)",
            description = """
                    paymentType:
                    - CARD (default) → Epoint widget URL in payment.paymentUrl
                    - KAPITAL → Kapital Bank HPP (card, no Taksit)
                    - INSTALLMENT + installmentProvider TAMKART|BOLKART|KAPITAL + installmentMonths → bank redirect URL
                      (KAPITAL Taksit: 3, 6 or 12 only; months cannot exceed package duration)
                    - APPLE_PAY → pre-priced Epoint Apple Pay widget URL in payment.paymentUrl
                    - GOOGLE_PAY → same as Apple Pay for Android WebView
                    - SAVED_CARD + cardId → charge saved Epoint card; paymentUrl null or 3DS redirect
                    Poll GET /api/v2/me/payments/order/{orderId} after opening paymentUrl.
                    """)
    public CheckoutCreateResponseV2 create(@Valid @RequestBody CheckoutCreateRequestV4 request) {
        return service.createV4(SubscriptionHomeStateController.requireUser(), request);
    }
}
