package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Presentation CMS only. This entity cannot change a subscription price. */
@Entity
@Table(name = "plan_campaigns")
@Getter @Setter
public class PlanCampaign {
    @Id private UUID id;
    @Column(nullable = false, unique = true) private String code;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private boolean enabled;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(name = "package_ids") private Long[] packageIds;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(name = "duration_months") private Integer[] durationMonths;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Content> content;
    @Version private long version;
    private OffsetDateTime updatedAt;
    private Long updatedBy;

    public record Content(String heading, String subtitle, String specialOffersTitle,
                          String bannerImageUrl, String darkBannerImageUrl,
                          String iconUrl, String darkIconUrl, String imageAlt) {}
}
