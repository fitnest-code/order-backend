package az.fitnest.order.controller;

import az.fitnest.order.dto.freeze.*;
import az.fitnest.order.service.freeze.SubscriptionFreezeService;
import az.fitnest.order.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Subscription Freeze (v1.1)", description = "Plan-level subscription freeze management endpoints")
public class SubscriptionFreezeController {

    private final SubscriptionFreezeService freezeService;
    private final az.fitnest.order.service.freeze.FreezeTermsService freezeTermsService;

    @Operation(summary = "Get freeze terms and conditions (HTML)", description = "İstifadəçi dilinə uyğun dondurma (freeze) qaydaları HTML sənədini qaytarır")
    @GetMapping("/freezes/terms")
    public ResponseEntity<FreezeTermsResponse> getTerms(
            @RequestParam(required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        String language = lang != null && !lang.isBlank() ? lang : acceptLanguage;
        return ResponseEntity.ok(freezeTermsService.getLocalizedTerms(language));
    }

    @Operation(summary = "Check freeze eligibility for a subscription")
    @GetMapping("/subscriptions/{id}/freeze")
    public ResponseEntity<FreezeEligibilityResponse> getEligibility(@PathVariable("id") Long subscriptionId) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(freezeService.getEligibility(userId, subscriptionId));
    }

    @Operation(summary = "Preview freeze effect before committing")
    @PostMapping("/subscriptions/{id}/freeze/preview")
    public ResponseEntity<FreezePreviewResponse> previewFreeze(
            @PathVariable("id") Long subscriptionId,
            @Valid @RequestBody FreezePreviewRequest request) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(freezeService.previewFreeze(userId, subscriptionId, request.days()));
    }

    @Operation(summary = "Commit a freeze for a subscription")
    @PostMapping("/subscriptions/{id}/freezes")
    public ResponseEntity<FreezeCommitResponse> commitFreeze(
            @PathVariable("id") Long subscriptionId,
            @Valid @RequestBody FreezeCommitRequest request,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(freezeService.commitFreeze(userId, subscriptionId, request, idempotencyKey));
    }

    @Operation(summary = "Preview resume effect for an active freeze")
    @PostMapping("/freezes/{id}/resume-preview")
    public ResponseEntity<ResumePreviewResponse> resumePreview(@PathVariable("id") Long freezeId) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(freezeService.resumePreview(userId, freezeId));
    }

    @Operation(summary = "Commit resume for an active freeze (end early)")
    @PostMapping("/freezes/{id}/resume")
    public ResponseEntity<ResumeCommitResponse> resumeCommit(
            @PathVariable("id") Long freezeId,
            @RequestBody(required = false) ResumeCommitRequest request,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(freezeService.resumeCommit(userId, freezeId, request, idempotencyKey));
    }

    @Operation(summary = "List freeze history for current user")
    @GetMapping("/me/freezes")
    public ResponseEntity<List<FreezeRecordDto>> listUserFreezes() {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(freezeService.listUserFreezes(userId));
    }

    @Operation(summary = "Get specific freeze details by ID")
    @GetMapping("/freezes/{id}")
    public ResponseEntity<FreezeRecordDto> getFreezeById(@PathVariable("id") Long freezeId) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(freezeService.getFreezeById(userId, freezeId));
    }
}
