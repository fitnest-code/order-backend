package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One rejected QR entry attempt (spec "visit_attempt").
 *
 * <p>Rows are append-only history/analytics: they never affect counters, limits or
 * settlement. A client-generated {@code qrScanId} is stored at most once, so retried
 * scans do not duplicate a row (partial unique index in V107). Deliberately not
 * extending an auditable base entity — the spec table only needs these columns and
 * keeping it lean keeps the insert cheap.
 */
@Entity
@Table(name = "visit_attempt", indexes = {
        @Index(name = "idx_visit_attempt_user_scanned", columnList = "user_id,scanned_at"),
        @Index(name = "idx_visit_attempt_sub_scanned", columnList = "subscription_id,scanned_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "gym_id", nullable = false)
    private Long gymId;

    /** Snapshot of the gym name at scan time, so renamed gyms keep their history. */
    @Column(name = "gym_name", length = 120)
    private String gymName;

    /** Subscriber package snapshot; may be null for order-side gates. */
    @Column(name = "package_code", length = 32)
    private String packageCode;

    /** STANDARD or HOME_GYM (HOME_GYM_PENDING is normalised to STANDARD). */
    @Column(name = "mode", nullable = false, length = 30)
    private String mode;

    @Column(name = "reject_code", nullable = false, length = 48)
    private String rejectCode;

    /** T-16 scan id; a partial unique index collapses retries. */
    @Column(name = "qr_scan_id", length = 120)
    private String qrScanId;

    @Column(name = "scanned_at", nullable = false)
    private LocalDateTime scannedAt;
}
