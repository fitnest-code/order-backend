package az.fitnest.order.controller;

import az.fitnest.order.repository.SubscriptionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Subscription Reports Admin", description = "Abunəliklər üzrə hesabat ucluqları")
@SecurityRequirement(name = "bearerAuth")
public class SubscriptionReportController {

    private final SubscriptionRepository subscriptionRepository;

    public record SubscriptionReportResponse(
        long activeSubscriptions,
        long endingSubscriptions,
        long renewingSubscriptions,
        double percentageChange,
        boolean isPositiveTrend
    ) {}

    @Operation(summary = "Abunəlik hesabat məlumatlarını gətir")
    @GetMapping("/subscriptions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubscriptionReportResponse> getSubscriptionReport(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        LocalDateTime end = endDate != null ? endDate : LocalDateTime.now();
        LocalDateTime start = startDate != null ? startDate : end.minusDays(30);

        long active = subscriptionRepository.countByStatus("ACTIVE");
        long ending = subscriptionRepository.countByStatusInAndEndAtBetween(List.of("ACTIVE"), start, end);
        long renewing = subscriptionRepository.countByIsUpgradedAndStartAtBetween(true, start, end);

        long days = Math.max(1, java.time.Duration.between(start, end).toDays());
        long startedNow = subscriptionRepository.countByStartAtBetween(start, end);
        long startedPrev = subscriptionRepository.countByStartAtBetween(start.minusDays(days), start);

        double pct = 0.0;
        if (startedPrev > 0) {
            pct = ((startedNow - startedPrev) * 100.0) / startedPrev;
        } else if (startedNow > 0) {
            pct = 100.0;
        }

        return ResponseEntity.ok(new SubscriptionReportResponse(
                active, ending, renewing, Math.abs(pct), startedNow >= startedPrev));
    }
}
