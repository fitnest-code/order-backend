package az.fitnest.order.dto.v4;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class PrimaryGymV4 {
  private PrimaryGymV4() {}

  public record Select(@NotNull @Positive Long gymId) {}

  public record CheckIn(
      @NotNull @Positive Long gymId, @NotBlank @Size(max = 100) String requestId) {}

  public record Cycle(LocalDateTime start, LocalDateTime end) {}

  public record History(
      Long id,
      Long gymId,
      String gymName,
      LocalDateTime selectedAt,
      LocalDateTime endedAt,
      String source,
      String endReason) {}

  public record Status(
      Long subscriptionId,
      Long packageId,
      Cycle cycle,
      Long gymId,
      String gymName,
      LocalDateTime selectedAt,
      String source,
      LocalDateTime expiresAt,
      long primaryVisits,
      int primaryLimit,
      long generalVisits,
      int generalLimit,
      boolean canChange,
      List<History> history,
      List<AccessWindow> accessibleHours) {}

  public record AccessWindow(
      Set<DayOfWeek> days,
      LocalTime start,
      LocalTime end,
      String priceTier,
      BigDecimal entryPrice,
      BigDecimal primaryMonthlyPrice) {}

  public record Visit(
      Long id,
      Long gymId,
      LocalDateTime checkedInAt,
      boolean primary,
      long generalVisits,
      long primaryVisits,
      BigDecimal entryPrice,
      BigDecimal monthlyPrice,
      String priceTier) {}

  public record Settlement(
      Long subscriptionId,
      Long gymId,
      Cycle cycle,
      long visits,
      boolean flatMonthly,
      BigDecimal amount,
      String currency) {}

  public record Affected(
      Long userId,
      Long subscriptionId,
      Long packageId,
      Long gymId,
      LocalDateTime selectedAt,
      LocalDateTime expiresAt,
      long cycleVisits) {}
}
