package az.fitnest.order.repository.promo;

import az.fitnest.order.model.entity.promo.PromoCampaignTranslation;
import az.fitnest.order.model.entity.promo.PromoCampaignTranslationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PromoCampaignTranslationRepository extends JpaRepository<PromoCampaignTranslation, PromoCampaignTranslationId> {

    List<PromoCampaignTranslation> findByCampaignId(UUID campaignId);

    void deleteByCampaignId(UUID campaignId);
}