package az.fitnest.order.pricingcampaign.repository;

import az.fitnest.order.pricingcampaign.entity.PricingCampaignLandingI18n;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PricingCampaignLandingI18nRepository extends JpaRepository<PricingCampaignLandingI18n, Long> {

    Optional<PricingCampaignLandingI18n> findByCampaignIdAndLocale(UUID campaignId, String locale);
}
