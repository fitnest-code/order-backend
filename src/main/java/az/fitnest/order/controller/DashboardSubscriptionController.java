package az.fitnest.order.controller;

import az.fitnest.order.dto.dashboard.DashboardHomeGymCountItem;
import az.fitnest.order.dto.dashboard.DashboardSubscriptionPage;
import az.fitnest.order.dto.dashboard.DashboardSubscriptionStartItem;
import az.fitnest.order.dto.dashboard.DashboardSubscriptionSummaryResponse;
import az.fitnest.order.service.dashboard.DashboardSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * Parallel dashboard subscription APIs. Leaves {@link SubscriptionReportController} unchanged.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard Subscriptions", description = "Admin dashboard subscription cards, lists, export")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'OWNER')")
public class DashboardSubscriptionController {

    private final DashboardSubscriptionService dashboardSubscriptionService;

    @Operation(summary = "Subscription summary + package cards")
    @GetMapping("/subscriptions/summary")
    public ResponseEntity<DashboardSubscriptionSummaryResponse> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ResponseEntity.ok(dashboardSubscriptionService.getSummary(dateFrom, dateTo));
    }

    @Operation(summary = "Paginated ACTIVE/FROZEN subscription list")
    @GetMapping("/subscriptions")
    public ResponseEntity<DashboardSubscriptionPage> list(
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(required = false) Long packageId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(dashboardSubscriptionService.list(status, packageId, page, size));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Operation(summary = "CSV export of ACTIVE/FROZEN subscriptions")
    @GetMapping(value = "/subscriptions/export", produces = "text/csv")
    public ResponseEntity<String> export(
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(required = false) Long packageId) {
        String csv;
        try {
            csv = dashboardSubscriptionService.exportCsv(status, packageId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=subscriptions.csv")
                .contentType(new MediaType("text", "csv"))
                .body(csv);
    }

    @Operation(summary = "Bulk purchase dates for dashboard customers")
    @GetMapping("/subscriptions/purchase-dates")
    public ResponseEntity<List<DashboardSubscriptionStartItem>> purchaseDates(
            @RequestParam List<Long> userIds) {
        return ResponseEntity.ok(dashboardSubscriptionService.purchaseDates(userIds));
    }

    @Operation(summary = "Active home-gym selection counts by gym")
    @GetMapping("/home-gym-counts")
    public ResponseEntity<List<DashboardHomeGymCountItem>> homeGymCounts() {
        return ResponseEntity.ok(dashboardSubscriptionService.homeGymCounts());
    }
}
