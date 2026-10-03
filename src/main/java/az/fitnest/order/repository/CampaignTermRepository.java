package az.fitnest.order.repository;

import az.fitnest.order.model.entity.CampaignTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CampaignTermRepository extends JpaRepository<CampaignTerm, Long> {
    List<CampaignTerm> findByCampaignIdOrderBySortOrderAsc(Long campaignId);

    // Flush deletes immediately so a delete-then-insert inside the same
    // transaction does not violate the (campaign_id, sort_order) unique constraint.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CampaignTerm t WHERE t.campaignId = :campaignId")
    void deleteByCampaignId(@Param("campaignId") Long campaignId);
}
