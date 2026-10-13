package az.fitnest.order.repository.promo;

import az.fitnest.order.model.entity.promo.PromoPartner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromoPartnerRepository extends JpaRepository<PromoPartner, UUID> {

    Optional<PromoPartner> findByName(String name);

    boolean existsByName(String name);

    List<PromoPartner> findByStatus(az.fitnest.order.model.entity.promo.PromoPartnerStatus status);

    @Query("SELECT p FROM PromoPartner p WHERE p.status = :status ORDER BY p.name")
    List<PromoPartner> findByStatusOrderByName(@Param("status") az.fitnest.order.model.entity.promo.PromoPartnerStatus status);
}