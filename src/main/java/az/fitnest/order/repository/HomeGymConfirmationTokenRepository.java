package az.fitnest.order.repository;

import az.fitnest.order.model.entity.HomeGymConfirmationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface HomeGymConfirmationTokenRepository extends JpaRepository<HomeGymConfirmationToken, Long> {

    Optional<HomeGymConfirmationToken> findByTokenAndUsedAtIsNull(String token);

    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
