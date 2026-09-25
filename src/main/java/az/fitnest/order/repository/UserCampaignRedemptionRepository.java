package az.fitnest.order.repository;

import az.fitnest.order.model.entity.UserCampaignRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserCampaignRedemptionRepository extends JpaRepository<UserCampaignRedemption, Long> {
    boolean existsByUserIdAndCampaignId(Long userId, Long campaignId);
    Optional<UserCampaignRedemption> findByUserIdAndCampaignId(Long userId, Long campaignId);
    Optional<UserCampaignRedemption> findBySubscriptionId(Long subscriptionId);
    void deleteBySubscriptionId(Long subscriptionId);
}
