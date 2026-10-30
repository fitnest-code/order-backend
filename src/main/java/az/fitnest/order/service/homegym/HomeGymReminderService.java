package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.event.SubscriptionEvent;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Push table row "24 saat sonra əsas zal seçilməyibsə" (spec §"Push").
 *
 * <p>The reminder is sent once per subscription, 24h after purchase. When that moment
 * falls between 22:00 and 09:00 it is moved to 10:00 (Baku) so the subscriber is not
 * woken up. It is skipped when a home gym is already selected (any history row), the
 * subscription is not active (cancelled/frozen/expired), it was refunded
 * ({@code subscriptions.refunded_at}, V19) or the package has no gym the subscriber may
 * pick. Only a push is sent: the in-app banner 5.1 (R9) is already visible until a home
 * gym is selected. Deep link: screen 1.3 (home-gym selection).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeGymReminderService {

    public static final String HOME_GYM_REMINDER = "HOME_GYM_REMINDER";
    /** Screen 1.3 — home-gym selection. */
    public static final String DEEP_LINK_HOME_GYM_SELECTION = "home-gym/select";

    private static final LocalTime QUIET_FROM = LocalTime.of(22, 0);
    private static final LocalTime QUIET_TO = LocalTime.of(9, 0);
    private static final LocalTime MORNING_SEND_AT = LocalTime.of(10, 0);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionHomeGymRepository homeGymRepository;
    private final CatalogServiceGrpcClient catalogClient;
    private final HomeGymPeriodCalculator periodCalculator;
    private final SubscriptionEventPublisher eventPublisher;

    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    public void sendDueReminders() {
        LocalDateTime now = periodCalculator.now();
        // Quiet hours are applied to the target moment, but a delayed run (deploy, backlog,
        // long outage) can arrive much later. Never push between 22:00 and 09:00: the next
        // run at/after 10:00 sends it, and the reminder stays "once per subscription".
        if (isQuietHour(now.toLocalTime())) {
            log.debug("Home-gym reminders paused during quiet hours ({})", now);
            return;
        }
        List<Subscription> candidates = subscriptionRepository
                .findHomeGymReminderCandidates(now.minusHours(24), now);
        int sent = 0;
        for (Subscription subscription : candidates) {
            if (now.isBefore(reminderAt(subscription.getStartAt()))) {
                continue;
            }
            // A selection was already made (active, changed or ended): the reminder is moot.
            if (homeGymRepository.existsBySubscriptionId(subscription.getSubscriptionId())) {
                markSent(subscription, now);
                continue;
            }
            boolean eligible = catalogClient.hasEligibleHomeGym(subscription.getPackageId());
            markSent(subscription, now);
            if (!eligible) {
                log.info("Skipping home-gym reminder for subscription {}: package {} has no eligible gym",
                        subscription.getSubscriptionId(), subscription.getPackageId());
                continue;
            }
            SubscriptionEvent event = new SubscriptionEvent(
                    subscription.getUserId(), HOME_GYM_REMINDER, subscription.getSubscriptionId());
            event.setDeepLink(DEEP_LINK_HOME_GYM_SELECTION);
            eventPublisher.publishSubscriptionEvent(subscription.getUserId(), event.getAction(),
                    subscription.getSubscriptionId(), event);
            sent++;
        }
        if (sent > 0) {
            log.info("Home-gym reminder sent for {} subscription(s)", sent);
        }
    }

    private void markSent(Subscription subscription, LocalDateTime now) {
        subscription.setHomeGymReminderSentAt(now);
        subscriptionRepository.save(subscription);
    }

    /**
     * Purchase + 24h, moved to 10:00 when it lands inside the quiet window:
     * 22:00–23:59 → next day 10:00, 00:00–08:59 → the same day 10:00.
     */
    static LocalDateTime reminderAt(LocalDateTime startAt) {
        if (startAt == null) {
            return LocalDateTime.now();
        }
        LocalDateTime target = startAt.plusHours(24);
        LocalTime time = target.toLocalTime();
        if (!time.isBefore(QUIET_FROM)) {
            return target.toLocalDate().plusDays(1).atTime(MORNING_SEND_AT);
        }
        if (time.isBefore(QUIET_TO)) {
            return target.toLocalDate().atTime(MORNING_SEND_AT);
        }
        return target;
    }

    /** 22:00–08:59 must never carry a push. */
    static boolean isQuietHour(LocalTime time) {
        return !time.isBefore(QUIET_FROM) || time.isBefore(QUIET_TO);
    }
}
