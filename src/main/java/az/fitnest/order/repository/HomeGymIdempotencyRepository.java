package az.fitnest.order.repository;

import az.fitnest.order.model.entity.HomeGymIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface HomeGymIdempotencyRepository extends JpaRepository<HomeGymIdempotency, Long> {

    Optional<HomeGymIdempotency> findByIdempotencyKey(String idempotencyKey);
}
