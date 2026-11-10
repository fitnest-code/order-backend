package az.fitnest.order.pricingcampaign.repository;

import az.fitnest.order.pricingcampaign.entity.PricingCampaignRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface PricingCampaignRedemptionRepository extends JpaRepository<PricingCampaignRedemption, Long> {

    boolean existsByCampaignIdAndUserId(UUID campaignId, Long userId);

    // Native ON CONFLICT: two concurrent payment callbacks must not blow up the transaction
    // (a failed JPA insert would mark it rollback-only).
    @Modifying
    @Query(value = "INSERT INTO pricing_campaign_redemptions (campaign_id, user_id, order_id, redeemed_at) "
            + "VALUES (:campaignId, :userId, :orderId, now()) ON CONFLICT (campaign_id, user_id) DO NOTHING",
            nativeQuery = true)
    int insertIgnoreDuplicate(@Param("campaignId") UUID campaignId,
                              @Param("userId") Long userId,
                              @Param("orderId") String orderId);
}
