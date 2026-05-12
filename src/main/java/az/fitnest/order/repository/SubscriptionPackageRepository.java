package az.fitnest.order.repository;

import az.fitnest.order.model.entity.SubscriptionPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Repository
public interface SubscriptionPackageRepository extends JpaRepository<SubscriptionPackage, Long> {
    @Query(value = "SELECT p FROM SubscriptionPackage p LEFT JOIN FETCH p.options LEFT JOIN FETCH p.benefits", countQuery = "SELECT COUNT(p) FROM SubscriptionPackage p")
    org.springframework.data.domain.Page<SubscriptionPackage> findAllWithOptions(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT p FROM SubscriptionPackage p LEFT JOIN FETCH p.options LEFT JOIN FETCH p.benefits")
    List<SubscriptionPackage> findAllWithOptions();
    @Query("""
    SELECT p FROM SubscriptionPackage p
    LEFT JOIN FETCH p.options o
    LEFT JOIN FETCH p.benefits
    WHERE p.id = :id
    """)
    java.util.Optional<SubscriptionPackage> findFullById(@Param("id") Long id);

    List<SubscriptionPackage> findByIsActiveTrue();

    @Query("SELECT p FROM SubscriptionPackage p LEFT JOIN FETCH p.options LEFT JOIN FETCH p.benefits WHERE p.id IN :ids")
    List<SubscriptionPackage> findAllByIdWithOptions(@Param("ids") List<Long> ids);
}
