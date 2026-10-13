package az.fitnest.order.repository.promo;

import az.fitnest.order.model.entity.promo.PromoGrant;
import az.fitnest.order.model.entity.promo.PromoGrantStatus;
import az.fitnest.order.model.entity.promo.PromoPopupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromoGrantRepository extends JpaRepository<PromoGrant, UUID> {

    Optional<PromoGrant> findByCampaignIdAndUserId(UUID campaignId, Long userId);

    List<PromoGrant> findByUserIdAndPopupStatusOrderByCreatedAtDesc(Long userId, PromoPopupStatus popupStatus);

    Optional<PromoGrant> findFirstByUserIdAndPopupStatusOrderByCreatedAtDesc(Long userId, PromoPopupStatus popupStatus);

    List<PromoGrant> findByStatusAndReservedUntilBefore(PromoGrantStatus status, OffsetDateTime now);

    List<PromoGrant> findByCampaignIdAndStatusIn(UUID campaignId, List<PromoGrantStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM PromoGrant g WHERE g.campaignId = :campaignId AND g.userId = :userId")
    Optional<PromoGrant> findByCampaignIdAndUserIdWithLock(@Param("campaignId") UUID campaignId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM PromoGrant g WHERE g.id = :id")
    Optional<PromoGrant> findByIdWithLock(@Param("id") UUID id);

    @Query("SELECT g FROM PromoGrant g WHERE g.campaignId = :campaignId AND g.phoneHash = :phoneHash AND g.status = :status")
    Optional<PromoGrant> findByCampaignIdAndPhoneHashAndStatus(@Param("campaignId") UUID campaignId, @Param("phoneHash") String phoneHash, @Param("status") PromoGrantStatus status);

    Page<PromoGrant> findByCampaignId(UUID campaignId, Pageable pageable);

    @Query("SELECT g FROM PromoGrant g WHERE g.campaignId = :campaignId AND g.status = :status")
    List<PromoGrant> findByCampaignIdAndStatus(@Param("campaignId") UUID campaignId, @Param("status") PromoGrantStatus status);

    Optional<PromoGrant> findByReservedOrderId(String reservedOrderId);

    @Query("SELECT g FROM PromoGrant g WHERE g.reservedOrderId = :orderId AND g.status = :status")
    List<PromoGrant> findByReservedOrderIdAndStatus(@Param("orderId") String orderId, @Param("status") PromoGrantStatus status);

    @Query("SELECT g FROM PromoGrant g WHERE g.reservedOrderId = :orderId OR g.redeemedOrderId = :orderId")
    List<PromoGrant> findByAnyOrderId(@Param("orderId") String orderId);

    @Query("SELECT COUNT(g) FROM PromoGrant g WHERE g.campaignId = :campaignId AND g.status = :status")
    long countByCampaignIdAndStatus(@Param("campaignId") UUID campaignId, @Param("status") PromoGrantStatus status);

    @Query("SELECT COUNT(g) FROM PromoGrant g WHERE g.campaignId = :campaignId AND g.status IN (:statuses)")
    long countByCampaignIdAndStatusIn(@Param("campaignId") UUID campaignId, @Param("statuses") List<PromoGrantStatus> statuses);

    @Query("SELECT COUNT(g) FROM PromoGrant g WHERE g.campaignId = :campaignId AND g.status = :status AND g.userId IS NOT NULL")
    long countRegisteredByCampaignId(@Param("campaignId") UUID campaignId, @Param("status") PromoGrantStatus status);

    @Query("SELECT COUNT(g) FROM PromoGrant g WHERE g.campaignId = :campaignId AND g.popupStatus = :status")
    long countPopupSeenByCampaignId(@Param("campaignId") UUID campaignId, @Param("status") PromoPopupStatus status);

    @Query("SELECT SUM(g.discountAmount) FROM PromoGrant g WHERE g.campaignId = :campaignId AND g.status = :status")
    BigDecimal sumDiscountAmountByCampaignIdAndStatus(@Param("campaignId") UUID campaignId, @Param("status") PromoGrantStatus status);
}