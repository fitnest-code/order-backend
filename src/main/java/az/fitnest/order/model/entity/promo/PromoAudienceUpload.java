package az.fitnest.order.model.entity.promo;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "promo_audience_upload")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PromoAudienceUpload {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private PromoUploadMode mode;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows = 0;

    @Column(name = "valid", nullable = false)
    private Integer valid = 0;

    @Column(name = "duplicates", nullable = false)
    private Integer duplicates = 0;

    @Column(name = "invalid", nullable = false)
    private Integer invalid = 0;

    @Column(name = "existing_users", nullable = false)
    private Integer existingUsers = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PromoUploadStatus status = PromoUploadStatus.PROCESSING;

    @Column(name = "report_url")
    private String reportUrl;

    @Column(name = "report_csv")
    private String reportCsv;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) uploadedAt = OffsetDateTime.now();
    }
}