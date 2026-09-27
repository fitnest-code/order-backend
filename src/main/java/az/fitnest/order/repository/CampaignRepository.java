package az.fitnest.order.repository;

import az.fitnest.order.model.entity.Campaign;
import az.fitnest.order.model.entity.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    Optional<Campaign> findByCode(String code);
    Optional<Campaign> findFirstByStatusAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
            CampaignStatus status, LocalDateTime startAt, LocalDateTime endAt);
    List<Campaign> findByStatusAndEndAtBefore(CampaignStatus status, LocalDateTime now);
}
