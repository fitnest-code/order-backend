package az.fitnest.order.repository.promo;

import az.fitnest.order.model.entity.promo.PromoAudienceUpload;
import az.fitnest.order.model.entity.promo.PromoUploadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromoAudienceUploadRepository extends JpaRepository<PromoAudienceUpload, UUID> {

    List<PromoAudienceUpload> findByCampaignIdOrderByUploadedAtDesc(UUID campaignId);

    Page<PromoAudienceUpload> findByCampaignId(UUID campaignId, Pageable pageable);

    Optional<PromoAudienceUpload> findByIdAndCampaignId(UUID id, UUID campaignId);
}