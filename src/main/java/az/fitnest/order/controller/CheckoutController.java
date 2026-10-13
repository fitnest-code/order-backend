package az.fitnest.order.controller;

import az.fitnest.order.dto.CheckoutRequest;
import az.fitnest.order.dto.CheckoutResponse;
import az.fitnest.order.service.CheckoutService;
import az.fitnest.order.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout", description = "Abunəliklər və idman paketləri almaq üçün ucluqlar")
@CrossOrigin
@RequiredArgsConstructor
@Slf4j
public class CheckoutController {

    private final CheckoutService checkoutService;

    @PostMapping
    @Operation(summary = "Place an order (POST /orders equivalent). promoCode optional.")
    public ResponseEntity<CheckoutResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
        Long userId = UserContext.getCurrentUserId();
        CheckoutResponse response = checkoutService.processCheckout(userId, request);
        return ResponseEntity.ok(response);
    }
}
