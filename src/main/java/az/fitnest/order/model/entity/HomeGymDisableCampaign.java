package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * R18: admin disabled "can be selected as home gym" for a gym.
 * Title/body are localized maps ({az,en,ru}) captured from the admin pop-up.
 */
@Entity
@Table(name = "home_gym_disable_campaign")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HomeGymDisableCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gym_id", nullable = false)
    private Long gymId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "title", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipients", columnDefinition = "jsonb")
    private List<Long> recipients;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "disabled_at", nullable = false)
    private LocalDateTime disabledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
