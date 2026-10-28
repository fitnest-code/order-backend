package az.fitnest.order.repository;

import az.fitnest.order.model.entity.PrimaryGymSelectionV4;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrimaryGymSelectionV4Repository
    extends JpaRepository<PrimaryGymSelectionV4, Long> {
  List<PrimaryGymSelectionV4> findBySubscriptionIdOrderBySelectedAtDescIdDesc(Long subscriptionId);

  List<PrimaryGymSelectionV4> findByGymIdAndEndedAtIsNull(Long gymId);
}
