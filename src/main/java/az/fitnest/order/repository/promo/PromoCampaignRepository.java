package az.fitnest.order.repository.promo;

import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoCampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromoCampaignRepository extends JpaRepository<PromoCampaign, UUID> {

    Optional<PromoCampaign> findByCodeNormalized(String codeNormalized);

    List<PromoCampaign> findByPartnerId(UUID partnerId);

    List<PromoCampaign> findByStatus(PromoCampaignStatus status);

    @Query("SELECT c FROM PromoCampaign c WHERE c.status = :status AND c.startsAt <= :now AND c.endsAt >= :now ORDER BY c.priority DESC, c.startsAt ASC")
    List<PromoCampaign> findActiveCampaigns(@Param("status") PromoCampaignStatus status, @Param("now") OffsetDateTime now);

    @Query("SELECT c FROM PromoCampaign c WHERE c.status IN (:statuses)")
    List<PromoCampaign> findByStatusIn(@Param("statuses") List<PromoCampaignStatus> statuses);

    @Query("SELECT c FROM PromoCampaign c WHERE c.status = :status AND c.startsAt <= :now")
    List<PromoCampaign> findScheduledToActivate(@Param("status") PromoCampaignStatus status, @Param("now") OffsetDateTime now);

    @Query("SELECT c FROM PromoCampaign c WHERE c.status IN (:statuses) AND c.endsAt <= :now")
    List<PromoCampaign> findCampaignsToEnd(@Param("statuses") List<PromoCampaignStatus> statuses, @Param("now") OffsetDateTime now);

    @Query("SELECT c FROM PromoCampaign c WHERE c.status IN (:statuses) AND c.totalLimit IS NOT NULL AND c.redeemedCount >= c.totalLimit")
    List<PromoCampaign> findCampaignsAtLimit(@Param("statuses") List<PromoCampaignStatus> statuses);

    Page<PromoCampaign> findAllByStatusIn(List<PromoCampaignStatus> statuses, Pageable pageable);

    @Query("SELECT c FROM PromoCampaign c WHERE c.partnerId = :partnerId AND c.status IN (:statuses)")
    List<PromoCampaign> findByPartnerIdAndStatusIn(@Param("partnerId") UUID partnerId, @Param("statuses") List<PromoCampaignStatus> statuses);

    @Query("SELECT c FROM PromoCampaign c WHERE c.id = :id")
    Optional<PromoCampaign> findByIdWithLock(@Param("id") UUID id);

    @Query("SELECT c FROM PromoCampaign c WHERE " +
            "(:search IS NULL OR LOWER(c.name) LIKE :search OR LOWER(c.code) LIKE :search) " +
            "AND (:partnerId IS NULL OR c.partnerId = :partnerId) " +
            "AND (:from IS NULL OR c.startsAt >= :from) " +
            "AND (:to IS NULL OR c.endsAt <= :to) " +
            "AND c.status IN :statuses")
    List<PromoCampaign> searchCampaigns(@Param("search") String search,
                                        @Param("statuses") List<PromoCampaignStatus> statuses,
                                        @Param("partnerId") UUID partnerId,
                                        @Param("from") OffsetDateTime from,
                                        @Param("to") OffsetDateTime to,
                                        Pageable pageable);

    @Query("SELECT COUNT(c) FROM PromoCampaign c WHERE " +
            "(:search IS NULL OR LOWER(c.name) LIKE :search OR LOWER(c.code) LIKE :search) " +
            "AND (:partnerId IS NULL OR c.partnerId = :partnerId) " +
            "AND (:from IS NULL OR c.startsAt >= :from) " +
            "AND (:to IS NULL OR c.endsAt <= :to) " +
            "AND c.status IN :statuses")
    long countSearchCampaigns(@Param("search") String search,
                              @Param("statuses") List<PromoCampaignStatus> statuses,
                              @Param("partnerId") UUID partnerId,
                              @Param("from") OffsetDateTime from,
                              @Param("to") OffsetDateTime to);
}