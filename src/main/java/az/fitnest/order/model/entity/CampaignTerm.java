package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "campaign_terms", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"campaign_id", "sort_order"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CampaignTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "is_positive", nullable = false)
    private Boolean isPositive;
}
