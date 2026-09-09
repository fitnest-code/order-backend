package az.fitnest.order.controller;

import az.fitnest.order.dto.ActiveSubscriptionResponse;
import az.fitnest.order.dto.ActiveSubscriptionResponseV2;
import az.fitnest.order.dto.ActiveSubscriptionResponseV3;
import az.fitnest.order.service.impl.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequiredArgsConstructor
@Tag(name = "User Subscriptions", description = "Autentifikasiya olunmuş istifadəçinin abunəliklərini idarə etmək üçün ucluqlar")
public class UserSubscriptionController {

    private final UserSubscriptionService subscriptionService;

    @Operation(summary = "Aktiv abunəliyi əldə edin (v1)", description = "Cari istifadəçi üçün aktiv abunəlik təfərrüatlarını qaytarır.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Aktiv abunəlik əldə edildi",
                    content = @Content(schema = @Schema(implementation = ActiveSubscriptionResponse.class))),
            @ApiResponse(responseCode = "401", description = "İcazə verilmədi")
    })
    @GetMapping("/api/v1/me/subscriptions/active")
    public ResponseEntity<ActiveSubscriptionResponse> getActiveSubscription() {
        Long userId = az.fitnest.order.util.UserContext.getCurrentUserId();
        return ResponseEntity.ok(subscriptionService.getActiveSubscription(userId));
    }

    @Operation(summary = "Aktiv abunəliyi əldə edin (v2)",
            description = "V1 abunəlik cavabı + FitNest Coin balansı və AZN ekvivalenti (payment gRPC).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Aktiv abunəlik və coin məlumatı əldə edildi",
                    content = @Content(schema = @Schema(implementation = ActiveSubscriptionResponseV2.class))),
            @ApiResponse(responseCode = "401", description = "İcazə verilmədi")
    })
    @GetMapping("/api/v2/me/subscriptions/active")
    public ResponseEntity<ActiveSubscriptionResponseV2> getActiveSubscriptionV2() {
        Long userId = az.fitnest.order.util.UserContext.getCurrentUserId();
        return ResponseEntity.ok(subscriptionService.getActiveSubscriptionV2(userId));
    }

    @Operation(summary = "Aktiv abunəliyi əldə edin (v3)",
            description = "Paket adı, plan müddəti (ay sayı), status və növbəti ödəniş tarixini qaytarır.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Aktiv abunəlik əldə edildi",
                    content = @Content(schema = @Schema(implementation = ActiveSubscriptionResponseV3.class))),
            @ApiResponse(responseCode = "401", description = "İcazə verilmədi")
    })
    @GetMapping("/api/v3/me/subscriptions/active")
    public ResponseEntity<ActiveSubscriptionResponseV3> getActiveSubscriptionV3() {
        Long userId = az.fitnest.order.util.UserContext.getCurrentUserId();
        return ResponseEntity.ok(subscriptionService.getActiveSubscriptionV3(userId));
    }

    @Operation(summary = "Abunəliyi dondur",
               description = "Cari istifadəçinin aktiv abunəliyini müəyyən müddətə dondurur. Dondurma müddəti bitmə tarixinə əlavə edilir.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Abunəlik donduruldu və gün əlavə edildi"),
            @ApiResponse(responseCode = "400", description = "Aktiv abunəlik yoxdur, bitib və ya dondurma limiti tükənib"),
            @ApiResponse(responseCode = "401", description = "İcazə verilmədi")
    })
    @PostMapping("/api/v1/me/subscriptions/freeze")
    public ResponseEntity<Void> freezeSubscription() {
        Long userId = az.fitnest.order.util.UserContext.getCurrentUserId();
        subscriptionService.freezeSubscription(userId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Abunəliyi aktivləşdirin",
               description = "Dondurulmuş abunəliyi yenidən aktivləşdirir. İstifadə olunmayan dondurma günləri geri qaytarılır.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Abunəlik aktivləşdirildi"),
            @ApiResponse(responseCode = "400", description = "Dondurulmuş abunəlik yoxdur"),
            @ApiResponse(responseCode = "401", description = "İcazə verilmədi")
    })
    @PostMapping("/api/v1/me/subscriptions/activate")
    public ResponseEntity<Void> activateSubscription() {
        Long userId = az.fitnest.order.util.UserContext.getCurrentUserId();
        subscriptionService.unfreezeSubscription(userId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Avtomatik ödənişi söndürün",
               description = "Cari istifadəçinin aktiv abunəliyi üçün avtomatik yenilənməni söndürür.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Avtomatik ödəniş söndürüldü"),
            @ApiResponse(responseCode = "400", description = "Aktiv abunəlik yoxdur"),
            @ApiResponse(responseCode = "401", description = "İcazə verilmədi")
    })
    @PostMapping("/api/v1/me/subscriptions/auto-payment/disable")
    public ResponseEntity<Void> disableAutoPayment() {
        Long userId = az.fitnest.order.util.UserContext.getCurrentUserId();
        subscriptionService.disableAutoPayment(userId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Avtomatik ödənişi aktivləşdirin",
               description = "Cari istifadəçinin aktiv abunəliyi üçün avtomatik yenilənməni aktivləşdirir (Yalnız 1 aylıq paketlər).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Avtomatik ödəniş aktivləşdirildi"),
            @ApiResponse(responseCode = "400", description = "Aktiv abunəlik yoxdur və ya 1 aylıq paket deyil"),
            @ApiResponse(responseCode = "401", description = "İcazə verilmədi")
    })
    @PostMapping("/api/v1/me/subscriptions/auto-payment/enable")
    public ResponseEntity<Void> enableAutoPayment() {
        Long userId = az.fitnest.order.util.UserContext.getCurrentUserId();
        subscriptionService.enableAutoPayment(userId);
        return ResponseEntity.ok().build();
    }
}
