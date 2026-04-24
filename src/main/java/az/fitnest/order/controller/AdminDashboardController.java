package az.fitnest.order.controller;

import az.fitnest.order.dto.dashboard.CustomerGrowthResponseDto;
import az.fitnest.order.dto.dashboard.FilterResponseDto;
import az.fitnest.order.dto.dashboard.RevenueResponseDto;
import az.fitnest.order.dto.dashboard.SummaryResponseDto;
import az.fitnest.order.service.impl.AdminDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@Tag(name = "Admin Dashboard", description = "Admin panel dashboard analytics endpoints")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'ACCOUNTANT', 'SUPPORT', 'GYM_OWNER')")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    @GetMapping("/filters")
    @Operation(summary = "Get dashboard filters")
    public FilterResponseDto getFilters(
            @Parameter(description = "Optional UI scope hint") @RequestParam(required = false) String role_scope) {
        return adminDashboardService.getFilters();
    }

    @GetMapping("/summary")
    @Operation(summary = "Get dashboard summary KPI cards")
    public SummaryResponseDto getSummary(
            @RequestParam(required = false, defaultValue = "last_30_days") String time_range,
            @RequestParam(required = false) Long gym_id,
            HttpServletRequest request) {
        return adminDashboardService.getSummary(time_range, gym_id, request);
    }

    @GetMapping("/revenue")
    @Operation(summary = "Get revenue chart data")
    public RevenueResponseDto getRevenue(
            @RequestParam(name = "package", required = false, defaultValue = "bronze") String packageCode,
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date_from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date_to,
            @RequestParam(required = false) Long gym_id,
            HttpServletRequest request) {
        return adminDashboardService.getRevenue(packageCode, period, date_from, date_to, gym_id, request);
    }

    @GetMapping("/customer-growth")
    @Operation(summary = "Get customer growth chart data")
    public CustomerGrowthResponseDto getCustomerGrowth(
            @RequestParam(required = false, defaultValue = "monthly") String period,
            @RequestParam(required = false, defaultValue = "new_customers") String metric,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date_from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date_to,
            @RequestParam(required = false) Long gym_id,
            HttpServletRequest request) {
        return adminDashboardService.getCustomerGrowth(period, metric, date_from, date_to, gym_id, request);
    }
}
