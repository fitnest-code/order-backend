package az.fitnest.order.repository;

import az.fitnest.order.model.entity.PlanCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PlanCampaignRepository extends JpaRepository<PlanCampaign, UUID> {
    java.util.Optional<PlanCampaign> findByCode(String code);
    List<PlanCampaign> findAllByOrderByCodeAsc();
    List<PlanCampaign> findByEnabledTrueOrderByCodeAsc();
}
