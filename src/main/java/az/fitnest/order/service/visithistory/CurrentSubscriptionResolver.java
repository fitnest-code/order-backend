package az.fitnest.order.service.visithistory;

import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the subscription the history and the rejection events talk about.
 *
 * <p>Mirrors the check-in "current subscription" rule without locking: ACTIVE/FINISHED
 * rows win (latest end date), and only when none exist the FROZEN row is used. Kept
 * separate from the check-in engine so read paths never take a write lock.
 */
@Component
@RequiredArgsConstructor
public class CurrentSubscriptionResolver {

    private final SubscriptionRepository subscriptionRepository;

    public Subscription resolve(Long userId) {
        if (userId == null) {
            return null;
        }
        List<Subscription> candidates = subscriptionRepository.findByUserIdAndStatusIn(
                userId, List.of("ACTIVE", "FINISHED"));
        if (candidates.isEmpty()) {
            return subscriptionRepository.findByUserIdAndStatus(userId, "FROZEN").stream()
                    .max((a, b) -> compareStartAt(a, b))
                    .orElse(null);
        }
        return candidates.stream()
                .max((a, b) -> compareEndAt(a, b))
                .orElse(null);
    }

    private int compareStartAt(Subscription a, Subscription b) {
        if (a.getStartAt() == null && b.getStartAt() == null) {
            return 0;
        }
        if (a.getStartAt() == null) {
            return -1;
        }
        if (b.getStartAt() == null) {
            return 1;
        }
        return a.getStartAt().compareTo(b.getStartAt());
    }

    private int compareEndAt(Subscription a, Subscription b) {
        if (a.getEndAt() != null && b.getEndAt() != null) {
            return a.getEndAt().compareTo(b.getEndAt());
        }
        if (a.getEndAt() != null) {
            return 1;
        }
        if (b.getEndAt() != null) {
            return -1;
        }
        return compareStartAt(a, b);
    }
}
