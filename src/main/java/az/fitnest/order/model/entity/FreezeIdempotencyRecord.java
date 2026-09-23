package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Idempotency guard for mutating freeze endpoints.
 *
 * Key: (userId, endpoint, idempotencyKey)
 * If a request arrives with the same key, return stored responseBody immediately.
 * TTL >= 24 hours (expires_at column).
 *
 * requestHash is a SHA-256 of the request body — used to detect replays with
 * a different body (should be rejected with 422, not replayed).
 */
@Entity
@Table(
    name = "freeze_idempotency_records",
    indexes = {
        @Index(name = "idx_idempotency_expires", columnList = "expires_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezeIdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Logical endpoint identifier, e.g. "freeze_commit" or "freeze_resume". */
    @Column(name = "endpoint", nullable = false, length = 60)
    private String endpoint;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** SHA-256 hex of canonicalised request payload. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** Serialised response JSON — returned on duplicate calls. */
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
