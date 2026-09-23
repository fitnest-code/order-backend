package az.fitnest.order.repository;

import az.fitnest.order.model.entity.SubscriptionFreezeEntitlement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SubscriptionFreezeEntitlementRepository extends JpaRepository<SubscriptionFreezeEntitlement, Long> {

    Optional<SubscriptionFreezeEntitlement> findBySubscriptionId(Long subscriptionId);

    /** Pessimistic write lock — used during freeze commit to prevent double-spend. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM SubscriptionFreezeEntitlement e WHERE e.subscriptionId = :subId")
    Optional<SubscriptionFreezeEntitlement> findBySubscriptionIdForUpdate(@Param("subId") Long subscriptionId);

    boolean existsBySubscriptionId(Long subscriptionId);

    /** Find active paid subscriptions that have no entitlement yet (for backfill job). */
    @Query("""
        SELECT s.subscriptionId FROM Subscription s
        WHERE s.status IN ('ACTIVE', 'FROZEN')
          AND NOT EXISTS (
              SELECT 1 FROM SubscriptionFreezeEntitlement e
              WHERE e.subscriptionId = s.subscriptionId
          )
        """)
    java.util.List<Long> findSubscriptionIdsWithoutEntitlement();
}
