package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaign_impressions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "campaign_id", "context"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CampaignImpression {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "context", nullable = false, length = 16)
    private String context;

    @Column(name = "last_shown_at", nullable = false)
    private LocalDateTime lastShownAt;
}
