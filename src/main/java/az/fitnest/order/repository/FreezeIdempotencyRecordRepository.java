package az.fitnest.order.repository;

import az.fitnest.order.model.entity.FreezeIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface FreezeIdempotencyRecordRepository extends JpaRepository<FreezeIdempotencyRecord, Long> {

    Optional<FreezeIdempotencyRecord> findByUserIdAndEndpointAndIdempotencyKey(
            Long userId, String endpoint, String idempotencyKey);

    @Modifying
    @Query("DELETE FROM FreezeIdempotencyRecord r WHERE r.expiresAt < :now")
    int deleteExpiredRecords(@Param("now") LocalDateTime now);
}
