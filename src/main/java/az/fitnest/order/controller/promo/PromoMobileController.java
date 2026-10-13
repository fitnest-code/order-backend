package az.fitnest.order.controller.promo;

import az.fitnest.order.dto.promo.*;
import az.fitnest.order.service.promo.PromoEligibilityService;
import az.fitnest.order.service.promo.PromoGrantService;
import az.fitnest.order.service.promo.PromoQuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/me/promo")
@Tag(name = "Promo (Mobile)", description = "Promo popup, banner and active codes for mobile app")
public class PromoMobileController {

    private final PromoEligibilityService eligibilityService;
    private final PromoGrantService grantService;
    private final PromoQuoteService quoteService;

    @GetMapping("/popups")
    @Operation(summary = "Get 0 or 1 pending popup")
    public ResponseEntity<Map<String, Object>> popup(
            @RequestHeader(name = "Accept-Language", defaultValue = "az") String lang) {
        MobilePromoPopupResponse popup = eligibilityService.pendingPopup(lang);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("popup", popup);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/popups/{grantId}/seen")
    @Operation(summary = "Mark popup as seen (idempotent, 204)")
    public ResponseEntity<Void> popupSeen(@PathVariable UUID grantId) {
        grantService.markPopupSeen(grantId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    @Operation(summary = "Codes user can use")
    public ResponseEntity<MobilePromoActiveResponse> active(
            @RequestParam(required = false) UUID planId,
            @RequestHeader(name = "Accept-Language", defaultValue = "az") String lang) {
        List<MobilePromoActiveItemResponse> items = eligibilityService.activeCodes(planId, lang);
        return ResponseEntity.ok(MobilePromoActiveResponse.builder().items(items).build());
    }
}
