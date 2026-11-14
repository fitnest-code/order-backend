package az.fitnest.order.model.entity.promo;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "promo_audience_number")
@IdClass(PromoAudienceNumberId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PromoAudienceNumber {

    @Id
    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Id
    @Column(name = "phone_hash", nullable = false, length = 64)
    private String phoneHash;

    @Column(name = "phone_encrypted")
    private byte[] phoneEncrypted;

    @Column(name = "upload_id", nullable = false)
    private UUID uploadId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", insertable = false, updatable = false)
    private PromoCampaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_id", insertable = false, updatable = false)
    private PromoAudienceUpload upload;
}