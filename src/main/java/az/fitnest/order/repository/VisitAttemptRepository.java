package az.fitnest.order.repository;

import az.fitnest.order.model.entity.VisitAttempt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface VisitAttemptRepository extends JpaRepository<VisitAttempt, Long> {

    /**
     * Race-free append that honours the partial unique index on {@code qr_scan_id}.
     * A retried scan (same userId/gym/qrScanId) is a no-op instead of an exception,
     * so a duplicate can never poison the surrounding check-in transaction.
     */
    @Modifying
    @Query(value = """
            INSERT INTO visit_attempt
                (subscription_id, user_id, gym_id, gym_name, package_code, mode,
                 reject_code, qr_scan_id, scanned_at)
            VALUES
                (:subscriptionId, :userId, :gymId, :gymName, :packageCode, :mode,
                 :rejectCode, :qrScanId, :scannedAt)
            ON CONFLICT (qr_scan_id) WHERE qr_scan_id IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("subscriptionId") Long subscriptionId,
                       @Param("userId") Long userId,
                       @Param("gymId") Long gymId,
                       @Param("gymName") String gymName,
                       @Param("packageCode") String packageCode,
                       @Param("mode") String mode,
                       @Param("rejectCode") String rejectCode,
                       @Param("qrScanId") String qrScanId,
                       @Param("scannedAt") LocalDateTime scannedAt);

    /**
     * Same null-parameter rule as {@code GymVisitRepository.findHistory}: flags stay
     * non-null so PostgreSQL can type every bind.
     */
    @Query("""
            SELECT a FROM VisitAttempt a
            WHERE a.userId = :userId
              AND a.scannedAt >= :from AND a.scannedAt < :to
              AND (:mode = 'ALL' OR a.mode = :mode)
              AND (:filterByGym = FALSE OR a.gymId = :gymId)
              AND (:hasCursor = FALSE
                   OR a.scannedAt < :cursorTs
                   OR (a.scannedAt = :cursorTs
                       AND (:cursorKind = 'VISIT' OR a.id < :cursorId)))
            ORDER BY a.scannedAt DESC, a.id DESC
            """)
    List<VisitAttempt> findHistory(@Param("userId") Long userId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to,
                                   @Param("mode") String mode,
                                   @Param("filterByGym") boolean filterByGym,
                                   @Param("gymId") Long gymId,
                                   @Param("hasCursor") boolean hasCursor,
                                   @Param("cursorTs") LocalDateTime cursorTs,
                                   @Param("cursorKind") String cursorKind,
                                   @Param("cursorId") Long cursorId,
                                   Pageable pageable);

    @Query("""
            SELECT COUNT(a) FROM VisitAttempt a
            WHERE a.userId = :userId
              AND a.scannedAt >= :from AND a.scannedAt < :to
              AND (:mode = 'ALL' OR a.mode = :mode)
            """)
    long countHistory(@Param("userId") Long userId,
                      @Param("from") LocalDateTime from,
                      @Param("to") LocalDateTime to,
                      @Param("mode") String mode);

    @Query("""
            SELECT a.gymId, COUNT(a) FROM VisitAttempt a
            WHERE a.userId = :userId
              AND a.scannedAt >= :from AND a.scannedAt < :to
              AND (:mode = 'ALL' OR a.mode = :mode)
            GROUP BY a.gymId
            """)
    List<Object[]> countHistoryByGym(@Param("userId") Long userId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     @Param("mode") String mode);

    @Query("""
            SELECT a.gymId, a.gymName, a.scannedAt FROM VisitAttempt a
            WHERE a.userId = :userId AND a.gymId IN :gymIds AND a.gymName IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM VisitAttempt a2
                  WHERE a2.userId = a.userId AND a2.gymId = a.gymId
                    AND a2.gymName IS NOT NULL
                    AND (a2.scannedAt > a.scannedAt
                         OR (a2.scannedAt = a.scannedAt AND a2.id > a.id)))
            """)
    List<Object[]> findLatestGymNames(@Param("userId") Long userId,
                                      @Param("gymIds") Collection<Long> gymIds);

    java.util.Optional<VisitAttempt> findFirstByUserIdOrderByScannedAtAsc(Long userId);
}
