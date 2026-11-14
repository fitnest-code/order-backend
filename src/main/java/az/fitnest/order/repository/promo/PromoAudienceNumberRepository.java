package az.fitnest.order.repository.promo;

import az.fitnest.order.model.entity.promo.PromoAudienceNumber;
import az.fitnest.order.model.entity.promo.PromoAudienceNumberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromoAudienceNumberRepository extends JpaRepository<PromoAudienceNumber, PromoAudienceNumberId> {

    Optional<PromoAudienceNumber> findByCampaignIdAndPhoneHash(UUID campaignId, String phoneHash);

    List<PromoAudienceNumber> findByCampaignId(UUID campaignId);

    List<PromoAudienceNumber> findByUploadId(UUID uploadId);

    @Query("SELECT COUNT(n) FROM PromoAudienceNumber n WHERE n.campaignId = :campaignId")
    long countByCampaignId(@Param("campaignId") UUID campaignId);

    @Query("SELECT n.phoneHash FROM PromoAudienceNumber n WHERE n.campaignId = :campaignId")
    List<String> findPhoneHashesByCampaignId(@Param("campaignId") UUID campaignId);

    /** All campaigns whose uploaded list contains this phone hash. */
    @Query("SELECT DISTINCT n.campaignId FROM PromoAudienceNumber n WHERE n.phoneHash = :phoneHash")
    List<UUID> findCampaignIdsByPhoneHash(@Param("phoneHash") String phoneHash);

    @Query("SELECT n.campaignId AS campaignId, COUNT(n) AS cnt FROM PromoAudienceNumber n " +
            "WHERE n.campaignId IN :ids GROUP BY n.campaignId")
    List<Object[]> countByCampaignIds(@Param("ids") List<UUID> ids);

    void deleteByCampaignId(UUID campaignId);

    void deleteByUploadId(UUID uploadId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM PromoAudienceNumber n WHERE n.campaignId = :campaignId AND n.uploadId <> :uploadId")
    void deleteByCampaignIdExcludingUpload(@Param("campaignId") UUID campaignId, @Param("uploadId") UUID uploadId);

    boolean existsByCampaignIdAndPhoneHash(UUID campaignId, String phoneHash);
}