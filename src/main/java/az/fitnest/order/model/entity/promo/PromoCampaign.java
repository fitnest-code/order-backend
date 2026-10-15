package az.fitnest.order.model.entity.promo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "promo_campaign")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PromoCampaign {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "partner_id")
    private UUID partnerId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "code_normalized", nullable = false, unique = true, length = 16)
    private String codeNormalized;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 16)
    private PromoDiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "max_discount_amount", precision = 10, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", nullable = false, length = 32)
    private PromoAudienceType audienceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false, length = 16)
    private PromoIdentifierType identifierType = PromoIdentifierType.PHONE;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_prefixes")
    private String[] allowedPrefixes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "plan_ids")
    private String[] planIds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tier_codes")
    private String[] tierCodes;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "uses_per_user", nullable = false)
    private Integer usesPerUser = 1;

    @Column(name = "total_limit")
    private Integer totalLimit;

    @Column(name = "redeemed_count", nullable = false)
    private Integer redeemedCount = 0;

    @Column(name = "first_payment_only", nullable = false)
    private Boolean firstPaymentOnly = true;

    @Column(name = "allow_fitcoin", nullable = false)
    private Boolean allowFitcoin = true;

    @Column(name = "allow_installments", nullable = false)
    private Boolean allowInstallments = true;

    @Column(name = "include_existing_users", nullable = false)
    private Boolean includeExistingUsers = true;

    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    @Column(name = "brand_color_override", length = 7)
    private String brandColorOverride;

    @Column(name = "logo_url_override")
    private String logoUrlOverride;

    @Column(name = "logo_on_dark_url_override")
    private String logoOnDarkUrlOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PromoCampaignStatus status = PromoCampaignStatus.DRAFT;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (codeNormalized == null && code != null) {
            codeNormalized = code.toUpperCase();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
        if (codeNormalized == null && code != null) {
            codeNormalized = code.toUpperCase();
        }
    }
}