package az.fitnest.order.controller.promo;

import az.fitnest.order.dto.promo.PromoQuoteRequest;
import az.fitnest.order.dto.promo.PromoQuoteResponse;
import az.fitnest.order.service.promo.PromoQuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout Quote", description = "Checkout quote with promo code support")
public class PromoQuoteController {

    private final PromoQuoteService quoteService;

    @PostMapping("/quote")
    @Operation(summary = "Quote for checkout, with optional promo code. Accepts JSON body (spec 9.3) or query params.")
    public ResponseEntity<PromoQuoteResponse> quote(
            @RequestBody(required = false) PromoQuoteRequest body,
            @RequestParam(name = "planId", required = false) String planIdParam,
            @RequestParam(name = "promoCode", required = false) String promoCodeParam,
            @RequestParam(name = "useFitCoin", required = false, defaultValue = "false") boolean useFitCoinParam,
            @RequestParam(name = "paymentType", required = false) String paymentTypeParam,
            @RequestParam(name = "basePrice", required = false) BigDecimal basePriceParam) {
        // Note: basePrice should be resolved server-side in production; caller may pass the plan's price.
        String planId = (body != null && body.getPlanId() != null) ? body.getPlanId() : planIdParam;
        String promoCode = (body != null && body.getPromoCode() != null) ? body.getPromoCode() : promoCodeParam;
        boolean useFitCoin = (body != null && Boolean.TRUE.equals(body.getUseFitCoin())) || useFitCoinParam;
        String paymentType = (body != null && body.getPaymentType() != null) ? body.getPaymentType() : paymentTypeParam;
        BigDecimal basePrice = (body != null && body.getBasePrice() != null) ? body.getBasePrice() : basePriceParam;
        PromoQuoteResponse response = quoteService.quote(planId, promoCode, useFitCoin, paymentType, basePrice);
        return ResponseEntity.ok(response);
    }
}
