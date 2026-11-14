package az.fitnest.order.repository.promo;

import az.fitnest.order.model.entity.promo.PromoCampaignPopup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromoCampaignPopupRepository extends JpaRepository<PromoCampaignPopup, UUID> {

    Optional<PromoCampaignPopup> findByCampaignId(UUID campaignId);

    List<PromoCampaignPopup> findByCampaignIdIn(List<UUID> campaignIds);

    void deleteByCampaignId(UUID campaignId);
}