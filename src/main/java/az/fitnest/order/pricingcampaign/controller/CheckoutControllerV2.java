package az.fitnest.order.pricingcampaign.controller;

import az.fitnest.order.pricingcampaign.dto.*;
import az.fitnest.order.pricingcampaign.service.PricingCheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/checkout")
@RequiredArgsConstructor
public class CheckoutControllerV2 {

    private final PricingCheckoutService service;

    @PostMapping("/preview")
    public CheckoutPreviewResponseV2 preview(@Valid @RequestBody CheckoutPreviewRequestV2 request) {
        return service.preview(SubscriptionHomeStateController.requireUser(), request);
    }

    @PostMapping
    public CheckoutCreateResponseV2 create(@Valid @RequestBody CheckoutCreateRequestV2 request) {
        return service.create(SubscriptionHomeStateController.requireUser(), request);
    }
}
