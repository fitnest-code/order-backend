package az.fitnest.order.service.homegym;

import az.fitnest.order.repository.GymVisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R19/R20 selection gate.
 *
 * <p>When no home gym is active the subscriber may only select one while the current
 * subscription month either has no visits at all (any eligible gym) or has all of its
 * visits at a single gym (only that gym, R19). Visits at two or more gyms close the
 * selection until the next subscription month.
 */
@Component
@RequiredArgsConstructor
public class HomeGymSelectionPolicy {

    public static final String MULTIPLE_GYMS_THIS_PERIOD = "MULTIPLE_GYMS_THIS_PERIOD";

    /**
     * @param open            selection is possible in this subscription month
     * @param blockedReason   stable API reason when {@code open} is false
     * @param soleGymId       the only visited gym of the period, when there is exactly one
     * @param soleGymVisits   countable visits at {@code soleGymId}
     * @param totalVisits     countable visits of the period, any gym
     * @param visitsByGym     countable visits grouped by gym
     */
    public record SelectionState(
            boolean open,
            String blockedReason,
            Long soleGymId,
            long soleGymVisits,
            long totalVisits,
            Map<Long, Long> visitsByGym) {

        public boolean multipleGyms() {
            return visitsByGym != null && visitsByGym.size() > 1;
        }

        public boolean noVisits() {
            return totalVisits == 0;
        }
    }

    private final GymVisitRepository gymVisitRepository;

    public SelectionState evaluate(Long subscriptionId, LocalDate periodStart) {
        Map<Long, Long> byGym = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : gymVisitRepository.countCountableVisitsByGym(subscriptionId, periodStart)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            Long gymId = ((Number) row[0]).longValue();
            long count = ((Number) row[1]).longValue();
            byGym.merge(gymId, count, Long::sum);
            total += count;
        }
        if (byGym.size() > 1) {
            return new SelectionState(false, MULTIPLE_GYMS_THIS_PERIOD, null, 0, total, byGym);
        }
        if (byGym.size() == 1) {
            Map.Entry<Long, Long> only = byGym.entrySet().iterator().next();
            return new SelectionState(true, null, only.getKey(), only.getValue(), total, byGym);
        }
        return new SelectionState(true, null, null, 0, 0, byGym);
    }
}
