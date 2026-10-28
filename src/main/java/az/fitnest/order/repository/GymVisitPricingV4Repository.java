package az.fitnest.order.repository;

import az.fitnest.order.model.entity.GymVisitPricingV4;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GymVisitPricingV4Repository extends JpaRepository<GymVisitPricingV4, Long> {
  Optional<GymVisitPricingV4> findByUserIdAndRequestId(Long userId, String requestId);

  List<GymVisitPricingV4> findBySubscriptionIdAndGymIdAndCycleStartOrderByCheckedInAtAscVisitIdAsc(
      Long subscriptionId, Long gymId, LocalDateTime cycleStart);
}
