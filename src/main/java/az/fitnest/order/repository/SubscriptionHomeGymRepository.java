package az.fitnest.order.repository;

import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.enums.HomeGymStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionHomeGymRepository extends JpaRepository<SubscriptionHomeGym, Long> {

    Optional<SubscriptionHomeGym> findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
            Long subscriptionId, HomeGymStatus status);

    List<SubscriptionHomeGym> findBySubscriptionIdAndStatus(Long subscriptionId, HomeGymStatus status);

    List<SubscriptionHomeGym> findBySubscriptionIdOrderBySelectedAtDesc(Long subscriptionId);

    List<SubscriptionHomeGym> findByGymIdAndStatus(Long gymId, HomeGymStatus status);

    @Query("SELECT h FROM SubscriptionHomeGym h WHERE h.status = az.fitnest.order.model.enums.HomeGymStatus.ACTIVE " +
            "AND h.endsAt IS NOT NULL AND h.endsAt <= :now")
    List<SubscriptionHomeGym> findDueForScheduledEnd(@Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM SubscriptionHomeGym h WHERE h.subscriptionId = :subscriptionId " +
            "AND h.status = az.fitnest.order.model.enums.HomeGymStatus.ACTIVE")
    Optional<SubscriptionHomeGym> findActiveForUpdate(@Param("subscriptionId") Long subscriptionId);

    @Query("SELECT DISTINCT h.subscriptionId FROM SubscriptionHomeGym h " +
            "WHERE h.gymId = :gymId AND h.status = az.fitnest.order.model.enums.HomeGymStatus.ACTIVE")
    List<Long> findActiveSubscriptionIdsByGymId(@Param("gymId") Long gymId);

    @Query("SELECT DISTINCT h.subscriptionId FROM SubscriptionHomeGym h " +
            "WHERE h.userId = :userId AND h.status = az.fitnest.order.model.enums.HomeGymStatus.ACTIVE")
    List<Long> findActiveSubscriptionIdsByUserId(@Param("userId") Long userId);

    boolean existsBySubscriptionIdAndStatus(Long subscriptionId, HomeGymStatus status);

    boolean existsBySubscriptionId(Long subscriptionId);

    long countByGymIdAndStatus(Long gymId, HomeGymStatus status);

    List<SubscriptionHomeGym> findByStatusAndEndReasonIsNull(HomeGymStatus status);
}
