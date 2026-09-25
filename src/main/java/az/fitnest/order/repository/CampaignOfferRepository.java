package az.fitnest.order.repository;

import az.fitnest.order.model.entity.CampaignOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignOfferRepository extends JpaRepository<CampaignOffer, Long> {
    List<CampaignOffer> findByCampaignId(Long campaignId);
    Optional<CampaignOffer> findByCampaignIdAndBaseDurationMonths(Long campaignId, Integer baseDurationMonths);
    void deleteByCampaignId(Long campaignId);
}
