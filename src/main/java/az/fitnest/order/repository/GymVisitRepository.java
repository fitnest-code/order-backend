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

    /** T-16: a retried scan must not create a second visit. */
    Optional<GymVisit> findFirstBySubscriptionIdAndQrScanIdOrderByCheckedInAtDesc(
            Long subscriptionId, String qrScanId);

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

    /** All subscription-months that have visits, used when a refund forces a recalculation. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT v.periodStart FROM GymVisit v " +
                    "WHERE v.subscriptionId = :subscriptionId AND v.periodStart IS NOT NULL " +
                    "ORDER BY v.periodStart")
    List<LocalDate> findDistinctPeriodsBySubscriptionId(
            @org.springframework.data.repository.query.Param("subscriptionId") Long subscriptionId);

    // ------------------------------------------------------------------
    // Visit history (QR history API)
    // ------------------------------------------------------------------

    /**
     * One page of successful visits. {@code mode} is {@code ALL}, {@code HOME_GYM} or
     * {@code STANDARD}; {@code HOME_GYM_PENDING} entries are stored as STANDARD.
     * The cursor tuple is (scannedAt, kind, id) descending; VISIT sorts before ATTEMPT
     * on equal timestamps, matching the in-memory merge in the history service.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT v FROM GymVisit v
            WHERE v.userId = :userId
              AND v.checkedInAt >= :from AND v.checkedInAt < :to
              AND (:mode = 'ALL'
                   OR (:mode = 'HOME_GYM'
                       AND v.entryType = az.fitnest.order.model.enums.VisitEntryType.HOME_UNLIMITED)
                   OR (:mode = 'STANDARD'
                       AND v.entryType = az.fitnest.order.model.enums.VisitEntryType.STANDARD))
              AND (:gymId IS NULL OR v.gymId = :gymId)
              AND (:cursorTs IS NULL
                   OR v.checkedInAt < :cursorTs
                   OR (v.checkedInAt = :cursorTs
                       AND :cursorKind = 'VISIT'
                       AND v.id < :cursorId))
            ORDER BY v.checkedInAt DESC, v.id DESC
            """)
    List<GymVisit> findHistory(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to,
            @org.springframework.data.repository.query.Param("mode") String mode,
            @org.springframework.data.repository.query.Param("gymId") Long gymId,
            @org.springframework.data.repository.query.Param("cursorTs") LocalDateTime cursorTs,
            @org.springframework.data.repository.query.Param("cursorKind") String cursorKind,
            @org.springframework.data.repository.query.Param("cursorId") Long cursorId,
            Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(v) FROM GymVisit v
            WHERE v.userId = :userId
              AND v.checkedInAt >= :from AND v.checkedInAt < :to
              AND (:mode = 'ALL'
                   OR (:mode = 'HOME_GYM'
                       AND v.entryType = az.fitnest.order.model.enums.VisitEntryType.HOME_UNLIMITED)
                   OR (:mode = 'STANDARD'
                       AND v.entryType = az.fitnest.order.model.enums.VisitEntryType.STANDARD))
            """)
    long countHistory(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to,
            @org.springframework.data.repository.query.Param("mode") String mode);

    @org.springframework.data.jpa.repository.Query("""
            SELECT v.gymId, COUNT(v) FROM GymVisit v
            WHERE v.userId = :userId
              AND v.checkedInAt >= :from AND v.checkedInAt < :to
              AND (:mode = 'ALL'
                   OR (:mode = 'HOME_GYM'
                       AND v.entryType = az.fitnest.order.model.enums.VisitEntryType.HOME_UNLIMITED)
                   OR (:mode = 'STANDARD'
                       AND v.entryType = az.fitnest.order.model.enums.VisitEntryType.STANDARD))
            GROUP BY v.gymId
            """)
    List<Object[]> countHistoryByGym(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to,
            @org.springframework.data.repository.query.Param("mode") String mode);

    @org.springframework.data.jpa.repository.Query("""
            SELECT v.gymId, v.gymName, v.checkedInAt FROM GymVisit v
            WHERE v.userId = :userId AND v.gymId IN :gymIds AND v.gymName IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM GymVisit v2
                  WHERE v2.userId = v.userId AND v2.gymId = v.gymId
                    AND v2.gymName IS NOT NULL
                    AND (v2.checkedInAt > v.checkedInAt
                         OR (v2.checkedInAt = v.checkedInAt AND v2.id > v.id)))
            """)
    List<Object[]> findLatestGymNames(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("gymIds") java.util.Collection<Long> gymIds);

    /**
     * Countable visits of one subscription month in chronological order. Used to derive
     * {@code counterAfter} for a page without an N+1 query per row.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT v FROM GymVisit v
            WHERE v.subscriptionId = :subscriptionId AND v.periodStart = :periodStart
              AND (v.countsTowardsLimit IS NULL OR v.countsTowardsLimit = TRUE)
            ORDER BY v.checkedInAt ASC, v.id ASC
            """)
    List<GymVisit> findCountableForCounter(
            @org.springframework.data.repository.query.Param("subscriptionId") Long subscriptionId,
            @org.springframework.data.repository.query.Param("periodStart") LocalDate periodStart);

    java.util.Optional<GymVisit> findFirstByUserIdOrderByCheckedInAtAsc(Long userId);

    java.util.Optional<GymVisit> findFirstBySubscriptionIdAndGymIdAndEntryTypeAndGymNameIsNotNullOrderByCheckedInAtDesc(
            Long subscriptionId, Long gymId, VisitEntryType entryType);
}
