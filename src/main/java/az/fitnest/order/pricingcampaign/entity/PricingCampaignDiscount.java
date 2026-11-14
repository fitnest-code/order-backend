package az.fitnest.order.pricingcampaign.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "pricing_campaign_discounts")
@Getter
@Setter
@NoArgsConstructor
public class PricingCampaignDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private PricingCampaign campaign;

    @Column(name = "months", nullable = false)
    private Integer months;

    @Column(name = "percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal percent;
}
