package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Table(
    name = "gym_visit_pricing_v4",
    uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "requestId"}))
@Getter
@Setter
@NoArgsConstructor
public class GymVisitPricingV4 {
  @Id private Long visitId;
  private Long userId;
  private String requestId;
  private Long subscriptionId;
  private Long gymId;
  private Long selectionId;
  private LocalDateTime checkedInAt;
  private LocalDateTime cycleStart;
  private LocalDateTime cycleEnd;
  private boolean primaryVisit;
  private long generalVisits;
  private long primaryVisits;

  @Column(precision = 12, scale = 2)
  private BigDecimal entryPrice;

  @Column(precision = 12, scale = 2)
  private BigDecimal monthlyPrice;

  private String priceTier;
  private long policyVersion;
}
