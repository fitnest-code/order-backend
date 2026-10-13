package az.fitnest.order.controller.promo;

import az.fitnest.order.dto.promo.PromoQuoteRequest;
import az.fitnest.order.dto.promo.PromoQuoteResponse;
import az.fitnest.order.service.promo.PromoQuoteService;
import az.fitnest.order.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout Quote", description = "Checkout quote with promo code support")
public class PromoQuoteController {

    private final PromoQuoteService quoteService;

    @PostMapping("/quote")
    @Operation(summary = "Quote for checkout, with optional promo code")
    public ResponseEntity<PromoQuoteResponse> quote(@RequestParam(name = "planId") UUID planId,
                                                   @RequestParam(name = "promoCode", required = false) String promoCode,
                                                   @RequestParam(name = "useFitCoin", defaultValue = "false") boolean useFitCoin,
                                                   @RequestParam(name = "paymentType") String paymentType,
                                                   @RequestParam(name = "basePrice", required = false) BigDecimal basePrice) {
        // Note: basePrice should be resolved server-side in production; caller may pass the plan's price.
        PromoQuoteResponse response = quoteService.quote(planId, promoCode, useFitCoin, paymentType, basePrice);
        return ResponseEntity.ok(response);
    }
}
