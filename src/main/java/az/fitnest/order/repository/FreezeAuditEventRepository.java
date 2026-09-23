package az.fitnest.order.repository;

import az.fitnest.order.model.entity.FreezeAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FreezeAuditEventRepository extends JpaRepository<FreezeAuditEvent, Long> {
    List<FreezeAuditEvent> findBySubscriptionIdOrderByCreatedAtDesc(Long subscriptionId);
    List<FreezeAuditEvent> findByUserIdOrderByCreatedAtDesc(Long userId);
}
