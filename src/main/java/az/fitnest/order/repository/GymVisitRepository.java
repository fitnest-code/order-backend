package az.fitnest.order.repository;

import az.fitnest.order.model.entity.GymVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GymVisitRepository extends JpaRepository<GymVisit, Long> {
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(v)
            FROM GymVisit v
            WHERE v.checkedInAt >= :from
              AND v.checkedInAt < :to
              AND (:gymId IS NULL OR v.gymId = :gymId)
            """)
    long countByPeriod(
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to,
            @org.springframework.data.repository.query.Param("gymId") Long gymId);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(DISTINCT v.gymId)
            FROM GymVisit v
            WHERE v.checkedInAt >= :from
              AND v.checkedInAt < :to
              AND (:gymId IS NULL OR v.gymId = :gymId)
            """)
    long countDistinctGymsByPeriod(
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to,
            @org.springframework.data.repository.query.Param("gymId") Long gymId);
}
