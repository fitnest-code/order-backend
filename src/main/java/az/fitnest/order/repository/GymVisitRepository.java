package az.fitnest.order.repository;

import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.enums.VisitEntryType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GymVisitRepository extends JpaRepository<GymVisit, Long> {

    void deleteByUserId(Long userId);

    long countBySubscriptionIdAndPeriodStartAndEntryType(
            Long subscriptionId, LocalDate periodStart, VisitEntryType entryType);

    long countBySubscriptionIdAndPeriodStartAndEntryTypeAndGymId(
            Long subscriptionId, LocalDate periodStart, VisitEntryType entryType, Long gymId);

    long countBySubscriptionIdAndGymIdAndPeriodStartAndEntryType(
            Long subscriptionId, Long gymId, LocalDate periodStart, VisitEntryType entryType);

    long countBySubscriptionIdAndPeriodStartAndGymId(
            Long subscriptionId, LocalDate periodStart, Long gymId);

    List<GymVisit> findBySubscriptionIdAndPeriodStartOrderByCheckedInAtAsc(
            Long subscriptionId, LocalDate periodStart);

    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT v.subscriptionId, v.periodStart FROM GymVisit v " +
                    "WHERE v.periodStart >= :from AND v.periodStart <= :to " +
                    "ORDER BY v.subscriptionId, v.periodStart")
    List<Object[]> findDistinctSubscriptionPeriods(
            @org.springframework.data.repository.query.Param("from") LocalDate from,
            @org.springframework.data.repository.query.Param("to") LocalDate to);

    List<GymVisit> findBySubscriptionIdAndPeriodStartBetweenOrderByCheckedInAtAsc(
            Long subscriptionId, LocalDate from, LocalDate to);

    List<GymVisit> findBySubscriptionIdAndGymIdAndPeriodStartOrderByCheckedInAtAsc(
            Long subscriptionId, Long gymId, LocalDate periodStart);

    List<GymVisit> findBySubscriptionIdAndGymIdOrderByCheckedInAtDesc(Long subscriptionId, Long gymId);

    List<GymVisit> findBySubscriptionIdOrderByCheckedInAtDesc(Long subscriptionId, Pageable pageable);

    org.springframework.data.domain.Page<GymVisit> findBySubscriptionIdAndEntryTypeOrderByCheckedInAtDesc(
            Long subscriptionId, VisitEntryType entryType, Pageable pageable);

    long countBySubscriptionId(Long subscriptionId);

    Optional<GymVisit> findFirstBySubscriptionIdAndGymIdAndEntryTypeOrderByCheckedInAtDesc(
            Long subscriptionId, Long gymId, VisitEntryType entryType);

    Optional<GymVisit> findFirstBySubscriptionIdAndGymIdAndEntryTypeAndPeriodStartOrderByCheckedInAtDesc(
            Long subscriptionId, Long gymId, VisitEntryType entryType, LocalDate periodStart);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(v) FROM GymVisit v WHERE v.subscriptionId = :subscriptionId " +
                    "AND v.periodStart = :periodStart AND v.entryType = :entryType " +
                    "AND (v.countsTowardsLimit IS NULL OR v.countsTowardsLimit = TRUE)")
    long countCountableBySubscriptionAndPeriodAndType(
            @org.springframework.data.repository.query.Param("subscriptionId") Long subscriptionId,
            @org.springframework.data.repository.query.Param("periodStart") LocalDate periodStart,
            @org.springframework.data.repository.query.Param("entryType") VisitEntryType entryType);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(v) FROM GymVisit v WHERE v.subscriptionId = :subscriptionId " +
                    "AND v.periodStart = :periodStart AND v.entryType = :entryType AND v.gymId = :gymId " +
                    "AND (v.countsTowardsLimit IS NULL OR v.countsTowardsLimit = TRUE)")
    long countCountableBySubscriptionAndPeriodAndTypeAndGym(
            @org.springframework.data.repository.query.Param("subscriptionId") Long subscriptionId,
            @org.springframework.data.repository.query.Param("periodStart") LocalDate periodStart,
            @org.springframework.data.repository.query.Param("entryType") VisitEntryType entryType,
            @org.springframework.data.repository.query.Param("gymId") Long gymId);

    List<GymVisit> findByCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
            LocalDateTime from, LocalDateTime to);

    List<GymVisit> findBySubscriptionIdAndCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
            Long subscriptionId, LocalDateTime from, LocalDateTime to);

    /**
     * R21 daily gate: countable home-gym visits of one subscription at one gym inside a
     * Baku calendar day.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(v) FROM GymVisit v WHERE v.subscriptionId = :subscriptionId " +
                    "AND v.gymId = :gymId AND v.entryType = :entryType " +
                    "AND v.checkedInAt >= :from AND v.checkedInAt < :to " +
                    "AND (v.countsTowardsLimit IS NULL OR v.countsTowardsLimit = TRUE)")
    long countCountableBySubscriptionAndGymAndTypeBetween(
            @org.springframework.data.repository.query.Param("subscriptionId") Long subscriptionId,
            @org.springframework.data.repository.query.Param("gymId") Long gymId,
            @org.springframework.data.repository.query.Param("entryType") VisitEntryType entryType,
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to);

    /**
     * R19/R20: countable visits of the subscription month grouped by gym, regardless of the
     * entry type (home and standard visits both count for the selection gate).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT v.gymId, COUNT(v) FROM GymVisit v WHERE v.subscriptionId = :subscriptionId " +
                    "AND v.periodStart = :periodStart " +
                    "AND (v.countsTowardsLimit IS NULL OR v.countsTowardsLimit = TRUE) " +
                    "GROUP BY v.gymId ORDER BY v.gymId")
    List<Object[]> countCountableVisitsByGym(
            @org.springframework.data.repository.query.Param("subscriptionId") Long subscriptionId,
            @org.springframework.data.repository.query.Param("periodStart") LocalDate periodStart);
}
