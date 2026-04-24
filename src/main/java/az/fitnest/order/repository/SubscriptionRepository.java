package az.fitnest.order.repository;

import az.fitnest.order.model.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
            @org.springframework.data.jpa.repository.Query("SELECT s FROM Subscription s WHERE s.status = 'FROZEN' AND s.unfreezesAt <= :now")
            java.util.List<Subscription> findExpiredFrozen(@org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    @org.springframework.data.jpa.repository.Query("SELECT s.userId FROM Subscription s JOIN PackageOption o ON s.packageId = o.subscriptionPackage.id WHERE o.durationMonths = :duration")
    java.util.List<Long> findUserIdsByDurationMonths(@org.springframework.data.repository.query.Param("duration") Integer duration);

            @org.springframework.data.jpa.repository.Query("SELECT s.userId FROM Subscription s")
            java.util.List<Long> findAllUserIds();
    List<Subscription> findByUserIdAndStatus(Long userId, String status);
    List<Subscription> findByStatusInAndEndAtBefore(List<String> statuses, LocalDateTime now);
    List<Subscription> findByStatusIn(List<String> statuses);
    List<Subscription> findByStatusInAndEndAtBetween(List<String> statuses, LocalDateTime start, LocalDateTime end);
    List<Subscription> findByStatus(String status);
    List<Subscription> findByPackageId(Long packageId);
    List<Subscription> findByIsUpgraded(Boolean isUpgraded);
    List<Subscription> findByStatusAndAutoPaymentEnabledAndEndAtBetween(String status, Boolean autoPaymentEnabled, LocalDateTime start, LocalDateTime end);
    @org.springframework.data.jpa.repository.Query("SELECT s FROM Subscription s WHERE s.userId = :userId AND s.status = :status ORDER BY s.startAt DESC, s.subscriptionId DESC")
    List<Subscription> findByUserIdAndStatusOrderByStartAtDesc(Long userId, String status);
    @org.springframework.data.jpa.repository.Query("SELECT s FROM Subscription s WHERE s.userId = :userId ORDER BY s.startAt DESC, s.subscriptionId DESC")
    List<Subscription> findAllByUserIdOrderByStartAtDesc(Long userId);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(DISTINCT s.userId)
            FROM Subscription s
            WHERE s.status IN :statuses
              AND s.startAt >= :from
              AND s.startAt < :to
              AND (:gymId IS NULL OR EXISTS (
                    SELECT 1 FROM GymVisit v
                    WHERE v.subscriptionId = s.subscriptionId
                      AND v.gymId = :gymId
              ))
            """)
    long countDistinctUsersByStatusAndPeriod(
            @org.springframework.data.repository.query.Param("statuses") List<String> statuses,
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to,
            @org.springframework.data.repository.query.Param("gymId") Long gymId);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(s)
            FROM Subscription s
            WHERE s.status IN :statuses
              AND s.startAt >= :from
              AND s.startAt < :to
              AND (:gymId IS NULL OR EXISTS (
                    SELECT 1 FROM GymVisit v
                    WHERE v.subscriptionId = s.subscriptionId
                      AND v.gymId = :gymId
              ))
            """)
    long countByStatusAndPeriod(
            @org.springframework.data.repository.query.Param("statuses") List<String> statuses,
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to,
            @org.springframework.data.repository.query.Param("gymId") Long gymId);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(DISTINCT s.userId)
            FROM Subscription s
            WHERE s.startAt >= :from
              AND s.startAt < :to
              AND (:gymId IS NULL OR EXISTS (
                    SELECT 1 FROM GymVisit v
                    WHERE v.subscriptionId = s.subscriptionId
                      AND v.gymId = :gymId
              ))
            """)
    long countNewCustomersByPeriod(
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to,
            @org.springframework.data.repository.query.Param("gymId") Long gymId);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COALESCE(SUM(p.price), 0)
            FROM Subscription s
            JOIN SubscriptionPackage p ON p.id = s.packageId
            WHERE s.status IN :statuses
              AND s.startAt >= :from
              AND s.startAt < :to
              AND (:packageCode = 'all' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :packageCode, '%')))
              AND (:gymId IS NULL OR EXISTS (
                    SELECT 1 FROM GymVisit v
                    WHERE v.subscriptionId = s.subscriptionId
                      AND v.gymId = :gymId
              ))
            """)
    java.math.BigDecimal sumRevenueByPeriod(
            @org.springframework.data.repository.query.Param("statuses") List<String> statuses,
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to,
            @org.springframework.data.repository.query.Param("packageCode") String packageCode,
            @org.springframework.data.repository.query.Param("gymId") Long gymId);
}
