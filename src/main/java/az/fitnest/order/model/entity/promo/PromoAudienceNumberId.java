package az.fitnest.order.model.entity.promo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PromoAudienceNumberId implements Serializable {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "phone_hash", nullable = false, length = 64)
    private String phoneHash;
}