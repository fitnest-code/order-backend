package az.fitnest.order.controller;

import az.fitnest.order.dto.homegym.HomeGymResponse;
import az.fitnest.order.dto.homegym.HomeGymSelectRequest;
import az.fitnest.order.dto.homegym.HomeGymVisitPage;
import az.fitnest.order.service.homegym.HomeGymService;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Home-gym API (spec §"Backend API").
 *
 * <p>Customer endpoints are scoped to the authenticated user ({@code X-User-Id}).
 * Admin endpoints mirror the read/change operations without the ownership check and are
 * protected by {@code ROLE_ADMIN}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class HomeGymController {

    private final HomeGymService homeGymService;

    @GetMapping("/subscriptions/{subscriptionId}/home-gym")
    public ResponseEntity<HomeGymResponse> getHomeGym(@PathVariable Long subscriptionId) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(homeGymService.getHomeGym(userId, subscriptionId));
    }

    @PutMapping("/subscriptions/{subscriptionId}/home-gym")
    public ResponseEntity<HomeGymResponse> selectHomeGym(
            @PathVariable Long subscriptionId,
            @RequestBody HomeGymSelectRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(homeGymService.selectHomeGym(userId, subscriptionId, request,
                idempotencyKey, false));
    }

    @GetMapping("/subscriptions/{subscriptionId}/home-gym/visits")
    public ResponseEntity<HomeGymVisitPage> getHomeGymVisits(
            @PathVariable Long subscriptionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(homeGymService.getVisits(userId, subscriptionId, page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/subscriptions/{subscriptionId}/home-gym")
    public ResponseEntity<HomeGymResponse> getHomeGymAsAdmin(@PathVariable Long subscriptionId) {
        return ResponseEntity.ok(homeGymService.getHomeGym(null, subscriptionId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/admin/subscriptions/{subscriptionId}/home-gym")
    public ResponseEntity<HomeGymResponse> selectHomeGymAsAdmin(
            @PathVariable Long subscriptionId,
            @RequestBody HomeGymSelectRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(homeGymService.selectHomeGym(userId, subscriptionId, request,
                idempotencyKey, true));
    }

    /**
     * R18 pop-up: subscribers who currently have this gym as their home gym.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/gyms/{gymId}/home-gym/subscribers")
    public ResponseEntity<List<Map<String, Object>>> getGymSubscribers(@PathVariable Long gymId) {
        return ResponseEntity.ok(homeGymService.getGymSubscribers(gymId));
    }
}
