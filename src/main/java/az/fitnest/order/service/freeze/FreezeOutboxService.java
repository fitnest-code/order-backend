package az.fitnest.order.service.freeze;

import az.fitnest.order.model.entity.FreezeOutboxEvent;
import az.fitnest.order.repository.FreezeOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreezeOutboxService {

    private final FreezeOutboxEventRepository outboxEventRepository;

    @Transactional
    public void recordEvent(String eventType, Long userId, Long freezeId, Map<String, Object> payload) {
        FreezeOutboxEvent event = FreezeOutboxEvent.builder()
                .eventType(eventType)
                .userId(userId)
                .freezeId(freezeId)
                .payload(payload)
                .published(false)
                .build();
        outboxEventRepository.save(event);
    }
}
