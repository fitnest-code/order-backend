package az.fitnest.order.event;

import az.fitnest.order.model.enums.VisitRejectCode;
import az.fitnest.order.service.homegym.HomeGymPeriodCalculator;
import az.fitnest.order.service.visithistory.VisitAttemptRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Persists rejections that catalog evaluates before calling order
 * (R14 time rules, package access and frozen subscriptions).
 *
 * <p>Catalog publishes {@code VISIT_ATTEMPT_REJECTED} after its scan transaction
 * commits. The insert is idempotent on {@code qrScanId}, so a Kafka redelivery or a
 * duplicated event cannot create two history rows. Malformed payloads are discarded
 * (they cannot succeed on retry) while genuine database failures roll back and retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitAttemptRejectedListener {

    public static final String TOPIC = "visit-attempt-events";
    public static final String EVENT_TYPE = "VISIT_ATTEMPT_REJECTED";

    private final ObjectMapper objectMapper;
    private final VisitAttemptRecorder attemptRecorder;
    private final HomeGymPeriodCalculator periodCalculator;

    @KafkaListener(topics = TOPIC, groupId = "order-visit-attempt-events-group")
    @Transactional
    public void consume(String message) {
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(message, Map.class);
        } catch (Exception e) {
            log.error("Discarding malformed visit-attempt event: {}", message, e);
            return;
        }
        if (!EVENT_TYPE.equals(stringValue(event.get("eventType")))) {
            return;
        }
        Long userId = longValue(event.get("userId"));
        Long gymId = longValue(event.get("gymId"));
        VisitRejectCode rejectCode = rejectCode(stringValue(event.get("rejectCode")));
        if (userId == null || gymId == null || rejectCode == null) {
            log.warn("Ignoring incomplete visit-attempt event: {}", message);
            return;
        }
        attemptRecorder.record(
                null,
                userId,
                gymId,
                stringValue(event.get("gymName")),
                stringValue(event.get("package")),
                rejectCode,
                stringValue(event.get("qrScanId")),
                scannedAt(event.get("scannedAt")));
    }

    private LocalDateTime scannedAt(Object value) {
        String raw = stringValue(value);
        if (raw == null || raw.isBlank()) {
            return periodCalculator.now();
        }
        try {
            return OffsetDateTime.parse(raw)
                    .atZoneSameInstant(HomeGymPeriodCalculator.BAKU)
                    .toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(raw);
            } catch (Exception ignoredAgain) {
                return periodCalculator.now();
            }
        }
    }

    private VisitRejectCode rejectCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VisitRejectCode.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring unknown visit reject code: {}", raw);
            return null;
        }
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
