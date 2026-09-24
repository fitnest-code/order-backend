package az.fitnest.order.service.freeze;

import az.fitnest.order.model.entity.SubscriptionFreeze;
import az.fitnest.order.repository.SubscriptionFreezeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionFreezeFinalizeWorker {

    private static final int BATCH_SIZE = 50;

    private final SubscriptionFreezeRepository freezeRepository;
    private final FreezeFinalizeService finalizeService;

    @Scheduled(fixedDelay = 60000)
    public void processExpiredFreezes() {
        LocalDateTime now = LocalDateTime.now();
        List<SubscriptionFreeze> expiredFreezes = freezeRepository.findExpiredActiveFreezes(now, BATCH_SIZE);

        if (expiredFreezes.isEmpty()) {
            return;
        }

        log.info("SubscriptionFreezeFinalizeWorker processing {} expired freezes (batch={})",
                expiredFreezes.size(), BATCH_SIZE);

        for (SubscriptionFreeze freeze : expiredFreezes) {
            try {
                finalizeService.complete(freeze);
            } catch (Exception e) {
                log.error("Failed to process expired freeze id={}: {}", freeze.getId(), e.getMessage(), e);
            }
        }
    }
}
