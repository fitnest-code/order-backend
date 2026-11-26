package az.fitnest.order.dto.v6;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Primary-gym check-in / settlement contracts for v6 (category-aware limits). */
public final class PrimaryGymV6 {
  private PrimaryGymV6() {}

  public static final String ROLE_MAIN = "MAIN";
  public static final String ROLE_SUB = "SUB";

  /**
   * Catalog resolves category role and snapshot prices before calling order. SUB must send
   * entryPrice only; MAIN sends membership entry + monthly from policy quote.
   */
  public record CheckIn(
      @NotNull @Positive Long gymId,
      @NotBlank @Size(max = 100) String requestId,
      @Positive Long categoryId,
      @Pattern(regexp = "MAIN|SUB") String categoryRole,
      @NotNull @DecimalMin("0.0") BigDecimal entryPrice,
      @DecimalMin("0.0") BigDecimal monthlyPrice) {}

  public record Visit(
      Long id,
      Long gymId,
      LocalDateTime checkedInAt,
      boolean primary,
      long generalVisits,
      long primaryVisits,
      BigDecimal entryPrice,
      BigDecimal monthlyPrice,
      String priceTier,
      Long categoryId,
      String categoryRole) {}

  public record Cycle(LocalDateTime start, LocalDateTime end) {}

  public record Settlement(
      Long subscriptionId,
      Long gymId,
      Cycle cycle,
      long visits,
      boolean flatMonthly,
      BigDecimal amount,
      String currency) {}
}
