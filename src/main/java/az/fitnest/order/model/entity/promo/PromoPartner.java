package az.fitnest.order.model.entity.promo;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "promo_partner")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PromoPartner {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 60)
    private String name;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "logo_on_dark_url")
    private String logoOnDarkUrl;

    @Column(name = "brand_color", length = 7)
    private String brandColor;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "agreement_ref")
    private String agreementRef;

    @Column(name = "retention_days", nullable = false)
    private Integer retentionDays = 90;

    @Column(name = "default_prefixes")
    private String[] defaultPrefixes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PromoPartnerStatus status = PromoPartnerStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}