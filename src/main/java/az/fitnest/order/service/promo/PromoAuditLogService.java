package az.fitnest.order.service.promo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit log for promo module (12.2).
 * Every write, publish, status change, upload, PII reveal and export is recorded.
 * Rows are kept for 1 year. Surfaced later in Settings -> Audit screen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromoAuditLogService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs in its own transaction: an audit write failure rolls back only the
     * audit row, never the business operation that triggered it.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void log(Long adminId, String action, String entityType, UUID entityId,
                    String beforeJson, String afterJson, String ip, String userAgent) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO promo_audit_log (id, admin_id, action, entity_type, entity_id, before_diff, after_diff, ip, user_agent, created_at) " +
                            "VALUES (?,?,?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)",
                    UUID.randomUUID(), adminId, action, entityType, entityId,
                    beforeJson, afterJson, ip, userAgent, OffsetDateTime.now());
        } catch (Exception e) {
            // Never break the main flow because of audit writes
            log.error("Failed to write promo audit log entry", e);
        }
    }
}
