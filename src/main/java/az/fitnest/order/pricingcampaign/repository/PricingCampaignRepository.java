package az.fitnest.order.pricingcampaign.repository;

import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PricingCampaignRepository extends JpaRepository<PricingCampaign, UUID> {

    // The time window is checked in PricingMath (testable); only the few ACTIVE rows are loaded.
    List<PricingCampaign> findByStatusAndType(PricingCampaignStatus status, String type);

    Optional<PricingCampaign> findByCode(String code);
}
