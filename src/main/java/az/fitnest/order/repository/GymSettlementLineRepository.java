package az.fitnest.order.repository;

import az.fitnest.order.model.entity.GymSettlementLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface GymSettlementLineRepository extends JpaRepository<GymSettlementLine, Long> {

    Optional<GymSettlementLine> findByGymIdAndSubscriptionIdAndPeriodStart(
            Long gymId, Long subscriptionId, LocalDate periodStart);

    List<GymSettlementLine> findByRunMonthOrderByGymIdAsc(String runMonth);
}
