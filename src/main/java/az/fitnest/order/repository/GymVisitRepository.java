package az.fitnest.order.repository;

import az.fitnest.order.model.entity.GymVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GymVisitRepository extends JpaRepository<GymVisit, Long> {
    void deleteByUserId(Long userId);
}
