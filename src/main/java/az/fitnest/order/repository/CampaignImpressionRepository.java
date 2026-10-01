package az.fitnest.order.repository;

import az.fitnest.order.model.entity.CampaignImpression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CampaignImpressionRepository extends JpaRepository<CampaignImpression, Long> {
    Optional<CampaignImpression> findByUserIdAndCampaignIdAndContext(Long userId, Long campaignId, String context);
}
