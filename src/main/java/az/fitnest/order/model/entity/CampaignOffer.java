package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "campaign_offers", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"campaign_id", "base_duration_months"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CampaignOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "base_duration_months", nullable = false)
    private Integer baseDurationMonths;

    @Column(name = "bonus_months", nullable = false)
    private Integer bonusMonths;
}
