package az.fitnest.order.repository;

import az.fitnest.order.model.entity.CampaignOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignOfferRepository extends JpaRepository<CampaignOffer, Long> {
    List<CampaignOffer> findByCampaignId(Long campaignId);
    Optional<CampaignOffer> findByCampaignIdAndBaseDurationMonths(Long campaignId, Integer baseDurationMonths);

    // Flush deletes immediately so a delete-then-insert inside the same
    // transaction does not violate the (campaign_id, base_duration_months) unique constraint.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CampaignOffer o WHERE o.campaignId = :campaignId")
    void deleteByCampaignId(@Param("campaignId") Long campaignId);
}
