package az.fitnest.order.repository;

import az.fitnest.order.model.entity.Translation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface TranslationRepository extends JpaRepository<Translation, Long> {
    boolean existsByEntityTypeAndEntityIdAndLanguageCodeAndFieldName(String entityType, String entityId, String languageCode, String fieldName);
    java.util.Optional<Translation> findFirstByEntityTypeAndEntityIdAndLanguageCodeAndFieldName(String entityType, String entityId, String languageCode, String fieldName);

    @Modifying
    @Transactional
    @Query("DELETE FROM Translation t WHERE t.entityType = :entityType AND t.entityId = :entityId")
    void deleteByEntityTypeAndEntityId(String entityType, String entityId);
}
