package az.fitnest.order.controller;

import az.fitnest.order.dto.v4.PrimaryGymV4.*;
import az.fitnest.order.service.PrimaryGymV4Service;
import az.fitnest.order.util.UserContext;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class PrimaryGymV4Controller {
  private final PrimaryGymV4Service service;

  @Value("${primary-gym.internal-key:}")
  private String internalKey;

  @GetMapping("/api/v4/me/subscriptions/primary-gym")
  public Status status() {
    return service.status(UserContext.getCurrentUserId());
  }

  @PutMapping("/api/v4/me/subscriptions/primary-gym")
  public Status select(@Valid @RequestBody Select request) {
    return service.select(UserContext.getCurrentUserId(), request.gymId(), "SUBSCRIPTION_SCREEN");
  }

  @GetMapping("/api/v4/admin/users/{userId}/primary-gym")
  @PreAuthorize("hasRole('ADMIN')")
  public Status adminStatus(@PathVariable Long userId) {
    return service.status(userId);
  }

  @PutMapping("/api/v4/admin/users/{userId}/primary-gym")
  @PreAuthorize("hasRole('ADMIN')")
  public Status adminSelect(@PathVariable Long userId, @Valid @RequestBody Select request) {
    return service.select(userId, request.gymId(), "ADMIN");
  }

  @GetMapping("/api/v4/admin/gyms/{gymId}/primary-subscribers")
  @PreAuthorize("hasRole('ADMIN')")
  public List<Affected> affected(@PathVariable Long gymId) {
    return service.affected(gymId);
  }

  @GetMapping("/api/v4/admin/gyms/{gymId}/settlements/{subscriptionId}")
  @PreAuthorize("hasRole('ADMIN')")
  public Settlement settlement(
      @PathVariable Long gymId,
      @PathVariable Long subscriptionId,
      @RequestParam LocalDateTime cycleStart) {
    return service.settlement(subscriptionId, gymId, cycleStart);
  }

  // Only catalog may commit a visit after QR/proximity checks. This path is never routed by the
  // gateway.
  @GetMapping("/api/v4/internal/primary-gym/gyms/{gymId}/subscribers")
  public List<Affected> internalAffected(
      @PathVariable Long gymId,
      @RequestHeader(value = "X-Primary-Gym-Key", defaultValue = "") String key) {
    validateKey(key);
    return service.affected(gymId);
  }

  private void validateKey(String key) {
    if (internalKey.isBlank()
        || !MessageDigest.isEqual(
            internalKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVALID_SERVICE_KEY");
  }

  @PostMapping("/api/v4/internal/primary-gym/check-ins")
  public Visit checkIn(
      @RequestHeader(value = "X-Primary-Gym-Key", defaultValue = "") String key,
      @Valid @RequestBody CheckIn request) {
    validateKey(key);
    return service.checkIn(UserContext.getCurrentUserId(), request);
  }
}
