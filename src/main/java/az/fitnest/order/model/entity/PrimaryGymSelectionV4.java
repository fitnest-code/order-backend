package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Table(name = "primary_gym_selections_v4")
@Getter
@Setter
@NoArgsConstructor
public class PrimaryGymSelectionV4 {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long userId;
  private Long subscriptionId;
  private Long gymId;
  private String gymName;
  private LocalDateTime selectedAt;
  private LocalDateTime endedAt;
  private String source;
  private String endReason;
}
