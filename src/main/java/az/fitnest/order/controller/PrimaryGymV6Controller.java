package az.fitnest.order.controller;

import az.fitnest.order.dto.v6.PrimaryGymV6.CheckIn;
import az.fitnest.order.dto.v6.PrimaryGymV6.Settlement;
import az.fitnest.order.dto.v6.PrimaryGymV6.Visit;
import az.fitnest.order.service.PrimaryGymV6Service;
import az.fitnest.order.util.UserContext;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * v6 primary-gym APIs. Parallel to {@link PrimaryGymV4Controller}; category visit gates live here
 * only.
 */
@RestController
@RequiredArgsConstructor
public class PrimaryGymV6Controller {

  private final PrimaryGymV6Service service;

  @Value("${primary-gym.internal-key:}")
  private String internalKey;

  @PostMapping("/api/v6/internal/primary-gym/check-ins")
  public Visit checkIn(
      @RequestHeader(value = "X-Primary-Gym-Key", defaultValue = "") String key,
      @Valid @RequestBody CheckIn request) {
    validateKey(key);
    return service.checkIn(UserContext.getCurrentUserId(), request);
  }

  @GetMapping("/api/v6/admin/gyms/{gymId}/settlements/{subscriptionId}")
  @PreAuthorize("hasRole('ADMIN')")
  public Settlement settlement(
      @PathVariable Long gymId,
      @PathVariable Long subscriptionId,
      @RequestParam LocalDateTime cycleStart) {
    return service.settlement(subscriptionId, gymId, cycleStart);
  }

  private void validateKey(String key) {
    if (internalKey.isBlank()
        || !MessageDigest.isEqual(
            internalKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVALID_SERVICE_KEY");
    }
  }
}
