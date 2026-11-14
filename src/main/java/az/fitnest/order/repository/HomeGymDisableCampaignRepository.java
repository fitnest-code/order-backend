package az.fitnest.order.repository;

import az.fitnest.order.model.entity.HomeGymDisableCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HomeGymDisableCampaignRepository extends JpaRepository<HomeGymDisableCampaign, Long> {
}
