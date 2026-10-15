package az.fitnest.order.repository.promo;

import az.fitnest.order.model.entity.promo.PromoCampaignMedia;
import az.fitnest.order.model.entity.promo.PromoCampaignMediaId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PromoCampaignMediaRepository extends JpaRepository<PromoCampaignMedia, PromoCampaignMediaId> {

    List<PromoCampaignMedia> findByCampaignId(UUID campaignId);

    void deleteByCampaignId(UUID campaignId);
}
