package az.fitnest.order.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "freeze_audit_events", indexes = {
        @Index(name = "idx_audit_freeze_id", columnList = "freeze_id"),
        @Index(name = "idx_audit_user_id", columnList = "user_id"),
        @Index(name = "idx_audit_sub_id", columnList = "subscription_id")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezeAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "freeze_id")
    private Long freezeId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
