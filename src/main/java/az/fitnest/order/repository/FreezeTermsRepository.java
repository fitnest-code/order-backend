package az.fitnest.order.repository;

import az.fitnest.order.model.entity.FreezeTerms;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FreezeTermsRepository extends JpaRepository<FreezeTerms, Long> {

    Optional<FreezeTerms> findFirstByOrderByIdAsc();
}
