package az.fitnest.order.service.freeze;

import az.fitnest.order.model.entity.FreezeOutboxEvent;
import az.fitnest.order.repository.FreezeOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class FreezeOutboxRelay {

    private final FreezeOutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String FREEZE_TOPIC = "subscription-freeze-events";

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void relayEvents() {
        List<FreezeOutboxEvent> events = outboxRepository.findUnpublished();
        if (events.isEmpty()) {
            return;
        }

        log.info("FreezeOutboxRelay publishing {} pending events", events.size());

        for (FreezeOutboxEvent event : events) {
            try {
                kafkaTemplate.send(FREEZE_TOPIC, String.valueOf(event.getUserId()), event)
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                outboxRepository.markPublished(event.getId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={}: {}", event.getId(), e.getMessage(), e);
            }
        }
    }
}
