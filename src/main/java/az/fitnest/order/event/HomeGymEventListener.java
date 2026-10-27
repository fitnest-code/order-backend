package az.fitnest.order.event;

import az.fitnest.order.model.entity.HomeGymDisableCampaign;
import az.fitnest.order.repository.HomeGymDisableCampaignRepository;
import az.fitnest.order.service.homegym.HomeGymPeriodCalculator;
import az.fitnest.order.service.homegym.HomeGymService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumes R18 events from catalog: the admin turned off "can be selected as home gym"
 * for a gym. Existing selections keep working until the end of each current subscription
 * month; new selections are rejected immediately because catalog exposes the flag.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeGymEventListener {

    public static final String HOME_GYM_DISABLED = "HOME_GYM_DISABLED";
    public static final String HOME_GYM_HOME_DISABLED = "HOME_GYM_HOME_DISABLED";

    private final ObjectMapper objectMapper;
    private final HomeGymDisableCampaignRepository campaignRepository;
    private final HomeGymService homeGymService;
    private final HomeGymPeriodCalculator periodCalculator;

    @KafkaListener(topics = "home-gym-events", groupId = "order-home-gym-events-group")
    @Transactional
    public void consumeHomeGymEvent(String message) {
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(message, Map.class);
        } catch (Exception e) {
            // Malformed payloads cannot succeed on retry; keep the consumer healthy.
            log.error("Discarding malformed home-gym event: {}", message, e);
            return;
        }

        String eventType = stringValue(event.get("eventType"));
        if (!HOME_GYM_DISABLED.equals(eventType) && !HOME_GYM_HOME_DISABLED.equals(eventType)) {
            return;
        }
        Long gymId = longValue(event.get("gymId"));
        if (gymId == null) {
            log.warn("Ignoring home-gym event without gymId: {}", message);
            return;
        }
        Map<String, String> title = localized(event.get("title"));
        Map<String, String> body = localized(event.get("body"));
        if (title.isEmpty() || body.isEmpty()) {
            log.warn("Ignoring home-gym event for gym {} without localized title/body", gymId);
            return;
        }
        List<Long> recipients = recipients(event.get("recipients"));
        if (recipients.isEmpty()) {
            recipients = homeGymService.resolveSubscriberUserIds(gymId);
        }

        // Failures must propagate so the transaction rolls back and Kafka can retry;
        // otherwise a partially applied disable campaign would be committed.
        HomeGymDisableCampaign campaign = campaignRepository.save(HomeGymDisableCampaign.builder()
                .gymId(gymId)
                .title(title)
                .body(body)
                .recipients(recipients)
                .createdBy(longValue(event.get("createdBy")))
                .disabledAt(periodCalculator.now())
                .createdAt(periodCalculator.now())
                .build());

        homeGymService.onGymHomeDisabled(gymId, campaign.getId(), title, body, recipients);
        log.info("Applied home-gym disable campaign {} for gym {} ({} recipients)",
                campaign.getId(), gymId, recipients.size());
    }

    private Map<String, String> localized(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, text) -> {
            if (key != null && text != null) {
                result.put(String.valueOf(key), String.valueOf(text));
            }
        });
        return result;
    }

    private List<Long> recipients(Object value) {
        List<Long> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Long id = longValue(item);
                if (id != null) {
                    result.add(id);
                }
            }
        }
        return result;
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
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
