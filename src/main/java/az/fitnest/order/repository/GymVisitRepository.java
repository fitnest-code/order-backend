package az.fitnest.order.repository;

import az.fitnest.order.model.entity.GymVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GymVisitRepository extends JpaRepository<GymVisit, Long> {
    java.util.List<GymVisit> findBySubscriptionIdAndCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
            Long subscriptionId, java.time.LocalDateTime start, java.time.LocalDateTime end);
    void deleteByUserId(Long userId);
}
