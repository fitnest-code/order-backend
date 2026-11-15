package az.fitnest.order.pricingcampaign.controller;

import az.fitnest.order.pricingcampaign.dto.*;
import az.fitnest.order.pricingcampaign.service.PricingCheckoutService;
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
public class CheckoutControllerV3 {

    private final PricingCheckoutService service;

    @PostMapping("/preview")
    public CheckoutPreviewResponseV2 preview(@Valid @RequestBody CheckoutPreviewRequestV2 request) {
        return service.preview(SubscriptionHomeStateController.requireUser(), request);
    }

    @PostMapping
    public CheckoutCreateResponseV2 create(@Valid @RequestBody CheckoutCreateRequestV3 request) {
        return service.createV3(SubscriptionHomeStateController.requireUser(), request);
    }
}
