package az.fitnest.order.repository;

import az.fitnest.order.model.entity.FreezePreview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface FreezePreviewRepository extends JpaRepository<FreezePreview, Long> {
    Optional<FreezePreview> findByIdAndUserId(Long id, Long userId);
    void deleteByExpiresAtBefore(LocalDateTime now);
}
