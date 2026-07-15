package az.fitnest.order.controller;

import az.fitnest.order.dto.AdminAssignSubscriptionRequest;
import az.fitnest.order.dto.AdminAssignSubscriptionResponse;
import az.fitnest.order.dto.ApiResponse;
import az.fitnest.order.service.impl.UserSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscription Admin", description = "İstifadəçi abunəliklərini idarə etmək üçün administrativ ucluqlar")
public class SubscriptionAdminController {

    private final UserSubscriptionService userSubscriptionService;

    @Operation(
            summary = "İstifadəçiyə abunəlik planı təyin edin",
            description = "Admin tərəfindən istifadəçiyə üzvlük planı təyin edir. " +
                    "Əgər istifadəçinin mövcud aktiv və ya dondurulmuş abunəliyi varsa, əvvəlki ləğv olunur."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Abunəlik uğurla təyin edildi",
                    content = @Content(schema = @Schema(implementation = AdminAssignSubscriptionResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Yanlış sorğu məlumatı"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Plan və ya müddət konfiqurasiyası tapılmadı")
    })
    @PostMapping("/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AdminAssignSubscriptionResponse>> assignSubscription(
            @Valid @RequestBody AdminAssignSubscriptionRequest request) {
        AdminAssignSubscriptionResponse response = userSubscriptionService.assignSubscriptionToUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "Remove all current subscriptions of a user",
        description = "Admin can remove all current subscriptions of a user (sets status to CANCELLED, does not delete records)."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "All subscriptions removed (cancelled) for user"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found or no subscriptions")
    })
    @DeleteMapping("/users/{userId}/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> removeAllSubscriptionsOfUser(@PathVariable Long userId) {
        userSubscriptionService.removeAllSubscriptionsOfUser(userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(
            summary = "İstifadəçinin cari abunəlik detallarını gətir",
            description = "İstifadəçinin ən son (cari) abunəlik məlumatlarını, paket detallarını və giriş limitlərini qaytarır."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Abunəlik detalları uğurla gətirildi",
                    content = @Content(schema = @Schema(implementation = az.fitnest.order.dto.AdminUserSubscriptionResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "İstifadəçi və ya abunəlik tapılmadı")
    })
    @GetMapping("/users/{userId}/current")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<az.fitnest.order.dto.AdminUserSubscriptionResponse>> getUserSubscriptionDetail(
            @PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(userSubscriptionService.getUserSubscriptionDetail(userId)));
    }

    @Operation(
            summary = "İstifadəçinin cari abunəliyinin giriş limitini yenilə",
            description = "Admin istifadəçinin cari abunəliyinin qalan (və istəyə bağlı ümumi) giriş limitini artıra və ya azalda bilər."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Giriş limiti uğurla yeniləndi",
                    content = @Content(schema = @Schema(implementation = az.fitnest.order.dto.AdminUserSubscriptionResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Yanlış sorğu məlumatı"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "İstifadəçi və ya abunəlik tapılmadı")
    })
    @PatchMapping("/users/{userId}/current/entry-limit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<az.fitnest.order.dto.AdminUserSubscriptionResponse>> updateEntryLimit(
            @PathVariable Long userId,
            @Valid @RequestBody az.fitnest.order.dto.UpdateEntryLimitRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userSubscriptionService.updateEntryLimit(userId, request)));
    }
}
