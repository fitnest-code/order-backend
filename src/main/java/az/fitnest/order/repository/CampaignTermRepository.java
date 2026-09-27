package az.fitnest.order.repository;

import az.fitnest.order.model.entity.CampaignTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CampaignTermRepository extends JpaRepository<CampaignTerm, Long> {
    List<CampaignTerm> findByCampaignIdOrderBySortOrderAsc(Long campaignId);
    void deleteByCampaignId(Long campaignId);
}
