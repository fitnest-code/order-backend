package az.fitnest.order.pricingcampaign.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pricing_campaigns")
@Getter
@Setter
@NoArgsConstructor
public class PricingCampaign {

    public static final String TYPE_BLACK_FRIDAY = "BLACK_FRIDAY";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "type", nullable = false, length = 32)
    private String type = TYPE_BLACK_FRIDAY;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PricingCampaignStatus status = PricingCampaignStatus.DRAFT;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "max_percent", nullable = false)
    private Integer maxPercent;

    @Column(name = "push_title_az") private String pushTitleAz;
    @Column(name = "push_title_ru") private String pushTitleRu;
    @Column(name = "push_title_en") private String pushTitleEn;
    @Column(name = "push_body_az", columnDefinition = "TEXT") private String pushBodyAz;
    @Column(name = "push_body_ru", columnDefinition = "TEXT") private String pushBodyRu;
    @Column(name = "push_body_en", columnDefinition = "TEXT") private String pushBodyEn;

    @Column(name = "push_last12h_title_az") private String pushLast12hTitleAz;
    @Column(name = "push_last12h_title_ru") private String pushLast12hTitleRu;
    @Column(name = "push_last12h_title_en") private String pushLast12hTitleEn;
    @Column(name = "push_last12h_body_az", columnDefinition = "TEXT") private String pushLast12hBodyAz;
    @Column(name = "push_last12h_body_ru", columnDefinition = "TEXT") private String pushLast12hBodyRu;
    @Column(name = "push_last12h_body_en", columnDefinition = "TEXT") private String pushLast12hBodyEn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // EAGER: a campaign is tiny (3 rows) and is always read together with its tiers.
    @OneToMany(mappedBy = "campaign", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("months ASC")
    private List<PricingCampaignDiscount> discounts = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
