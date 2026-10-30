package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

/** Limits the new image-delivery endpoints to files uploaded through this CMS. */
@Entity @Table(name = "plan_campaign_images") @Getter @Setter
public class PlanCampaignImage {
    @Id @Column(name = "fs_id", length = 32) private String fsId;
    private Long uploadedBy;
    private OffsetDateTime uploadedAt;
}
