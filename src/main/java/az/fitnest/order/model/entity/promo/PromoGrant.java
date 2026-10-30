package az.fitnest.order.model.entity.promo;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "promo_grant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PromoGrant {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "phone_hash", nullable = false, length = 64)
    private String phoneHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PromoGrantStatus status = PromoGrantStatus.GRANTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "popup_status", nullable = false, length = 16)
    private PromoPopupStatus popupStatus = PromoPopupStatus.PENDING;

    @Column(name = "popup_seen_at")
    private OffsetDateTime popupSeenAt;

    @Column(name = "reserved_order_id", length = 128)
    private String reservedOrderId;

    @Column(name = "reserved_until")
    private OffsetDateTime reservedUntil;

    @Column(name = "redeemed_order_id", length = 128)
    private String redeemedOrderId;

    @Column(name = "payment_id", length = 128)
    private String paymentId;

    @Column(name = "base_amount", precision = 10, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "discount_amount", precision = 10, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "final_amount", precision = 10, scale = 2)
    private BigDecimal finalAmount;

    @Column(name = "redeemed_at")
    private OffsetDateTime redeemedAt;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}