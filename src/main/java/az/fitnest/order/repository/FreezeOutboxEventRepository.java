package az.fitnest.order.repository;

import az.fitnest.order.model.entity.FreezeOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FreezeOutboxEventRepository extends JpaRepository<FreezeOutboxEvent, UUID> {

    /** Relay fetches unpublished events ordered by creation time. */
    @Query("SELECT e FROM FreezeOutboxEvent e WHERE e.published = false ORDER BY e.createdAt ASC")
    List<FreezeOutboxEvent> findUnpublished();

    @Modifying
    @Query("UPDATE FreezeOutboxEvent e SET e.published = true WHERE e.id = :id")
    void markPublished(@Param("id") UUID id);
}
