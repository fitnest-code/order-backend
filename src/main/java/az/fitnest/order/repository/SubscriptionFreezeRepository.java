package az.fitnest.order.repository;

import az.fitnest.order.model.entity.SubscriptionFreeze;
import az.fitnest.order.model.enums.FreezeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SubscriptionFreezeRepository extends JpaRepository<SubscriptionFreeze, Long> {

    Optional<SubscriptionFreeze> findBySubscriptionIdAndStatus(Long subscriptionId, FreezeStatus status);

    List<SubscriptionFreeze> findByUserIdAndStatus(Long userId, FreezeStatus status);

    /** Worker batch — indexed partial index on (plan_end_at) WHERE status=ACTIVE. */
    @Query(value = """
            SELECT * FROM subscription_freezes f
            WHERE f.status = 'ACTIVE' AND f.plan_end_at <= :now
            ORDER BY f.plan_end_at ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<SubscriptionFreeze> findExpiredActiveFreezes(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM SubscriptionFreeze f WHERE f.id = :id")
    Optional<SubscriptionFreeze> findByIdForUpdate(@Param("id") Long id);

    boolean existsBySubscriptionIdAndStatus(Long subscriptionId, FreezeStatus status);

    List<SubscriptionFreeze> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
}
