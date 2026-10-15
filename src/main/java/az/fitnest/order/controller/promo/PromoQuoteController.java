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

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout Quote", description = "Checkout quote with promo code support")
public class PromoQuoteController {

    private final PromoQuoteService quoteService;

    @PostMapping("/quote")
    @Operation(summary = "Quote for checkout. Price resolved server-side from packageId+optionId (B12). "
            + "Accepts JSON body (spec 9.3) or query params. planId is a deprecated alias of packageId.")
    public ResponseEntity<PromoQuoteResponse> quote(
            @RequestBody(required = false) PromoQuoteRequest body,
            @RequestParam(name = "packageId", required = false) String packageIdParam,
            @RequestParam(name = "planId", required = false) String planIdParam,
            @RequestParam(name = "optionId", required = false) Long optionIdParam,
            @RequestParam(name = "promoCode", required = false) String promoCodeParam,
            @RequestParam(name = "useFitCoin", required = false, defaultValue = "false") boolean useFitCoinParam,
            @RequestParam(name = "paymentType", required = false) String paymentTypeParam,
            @RequestParam(name = "installmentProvider", required = false) String installmentProviderParam,
            @RequestParam(name = "installmentMonths", required = false) Integer installmentMonthsParam) {
        String packageId;
        Long optionId;
        String promoCode;
        boolean useFitCoin;
        String paymentType;
        String installmentProvider;
        Integer installmentMonths;
        if (body != null && (body.getPackageId() != null || body.getPlanId() != null)) {
            packageId = body.getPackageId() != null ? body.getPackageId() : body.getPlanId();
            optionId = body.getOptionId();
            promoCode = body.getPromoCode();
            useFitCoin = Boolean.TRUE.equals(body.getUseFitCoin());
            paymentType = body.getPaymentType();
            installmentProvider = body.getInstallmentProvider();
            installmentMonths = body.getInstallmentMonths();
        } else {
            packageId = packageIdParam != null ? packageIdParam : planIdParam;
            optionId = optionIdParam;
            promoCode = promoCodeParam;
            useFitCoin = useFitCoinParam;
            paymentType = paymentTypeParam;
            installmentProvider = installmentProviderParam;
            installmentMonths = installmentMonthsParam;
        }
        PromoQuoteResponse response = quoteService.quote(packageId, optionId, promoCode,
                useFitCoin, paymentType, installmentProvider, installmentMonths);
        return ResponseEntity.ok(response);
    }
}
