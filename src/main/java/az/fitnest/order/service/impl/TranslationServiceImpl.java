package az.fitnest.order.service.impl;

import az.fitnest.order.model.entity.Translation;
import az.fitnest.order.repository.TranslationRepository;
import az.fitnest.order.service.TranslationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TranslationServiceImpl implements TranslationService {

    private final TranslationRepository translationRepository;
    private static final Logger log = LoggerFactory.getLogger(TranslationServiceImpl.class);

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    private TranslationEntityResolver translationEntityResolver;

    @Override
    public String getTranslatedValue(String entityType, String entityId, String fieldName, String languageCode) {
        if (entityType == null || entityId == null || fieldName == null) {
            return null;
        }
        if (languageCode == null) {
            languageCode = "AZ";
        }
        
        if (languageCode.equalsIgnoreCase("AZ")) {
            // For AZ, try to load from translations table directly
            String existingValue = translationRepository.findFirstByEntityTypeAndEntityIdAndLanguageCodeAndFieldName(
                            entityType.toUpperCase(),
                            entityId,
                            "AZ",
                            fieldName)
                    .map(Translation::getFieldValue)
                    .orElse(null);
            if (existingValue != null && !existingValue.trim().isEmpty()) {
                return existingValue;
            }
            // Fallback: For FREEZE_TERMS, fetch base content from entity directly
            if ("FREEZE_TERMS".equalsIgnoreCase(entityType)) {
                try {
                    Class<?> entityClass = translationEntityResolver.getEntityClass(entityType);
                    if (entityClass != null) {
                        Object entity = entityManager.find(entityClass, Long.parseLong(entityId));
                        if (entity != null) {
                            String baseContent = translationEntityResolver.extractFieldValue(entity, fieldName);
                            if (baseContent != null && !baseContent.trim().isEmpty()) {
                                return baseContent;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch AZ base content for FREEZE_TERMS: {}", e.getMessage());
                }
            }
            return null;
        }

        if (entityType != null) {
            String normType = entityType.toUpperCase();
            if (normType.equals("SUBSCRIPTION_STATUS")) {
                String status = entityId.toUpperCase();
                if (languageCode.equalsIgnoreCase("EN")) {
                    switch (status) {
                        case "ACTIVE": return "Active";
                        case "FINISHED": return "Finished";
                        case "FROZEN": return "Frozen";
                        case "PENDING": return "Pending";
                        case "CANCELLED": return "Cancelled";
                        case "EXPIRED": return "Expired";
                        case "NONE": return "No Plan";
                        default: return entityId;
                    }
                } else if (languageCode.equalsIgnoreCase("RU")) {
                    switch (status) {
                        case "ACTIVE": return "Активный";
                        case "FINISHED": return "Завершен";
                        case "FROZEN": return "Заморожен";
                        case "PENDING": return "В ожидании";
                        case "CANCELLED": return "Отменен";
                        case "EXPIRED": return "Истек";
                        case "NONE": return "Нет плана";
                        default: return entityId;
                    }
                }
                return entityId;
            } else if (normType.equals("DURATION")) {
                try {
                    int months = Integer.parseInt(entityId);
                    if (languageCode.equalsIgnoreCase("EN")) {
                        return months == 1 ? "1 month" : months + " months";
                    } else if (languageCode.equalsIgnoreCase("RU")) {
                        if (months == 1) return "1 месяц";
                        if (months >= 2 && months <= 4) return months + " месяца";
                        return months + " месяцев";
                    }
                } catch (Exception ignored) {
                }
                return entityId + " ay";
            } else if (normType.equals("ORDER_STATUS") || normType.equals("ORDERSTATUS")) {
                String status = entityId.toUpperCase();
                if (languageCode.equalsIgnoreCase("EN")) {
                    switch (status) {
                        case "PENDING": return "Pending";
                        case "SUCCESS": return "Success";
                        case "FAILED": return "Failed";
                        default: return entityId;
                    }
                } else if (languageCode.equalsIgnoreCase("RU")) {
                    switch (status) {
                        case "PENDING": return "В ожидании";
                        case "SUCCESS": return "Успешно";
                        case "FAILED": return "Ошибка";
                        default: return entityId;
                    }
                }
                return entityId;
            } else if (normType.equals("FREEZE_STATUS") || normType.equals("FREEZESTATUS")) {
                String status = entityId.toUpperCase();
                if (languageCode.equalsIgnoreCase("EN")) {
                    switch (status) {
                        case "ACTIVE": return "Frozen";
                        case "COMPLETED": return "Activated";
                        case "ENDED_EARLY": return "Activated early";
                        case "TERMINATED": return "Terminated";
                        default: return entityId;
                    }
                } else if (languageCode.equalsIgnoreCase("RU")) {
                    switch (status) {
                        case "ACTIVE": return "Заморожен";
                        case "COMPLETED": return "Активировано";
                        case "ENDED_EARLY": return "Активировано досрочно";
                        case "TERMINATED": return "Прекращён";
                        default: return entityId;
                    }
                }
                return entityId;
            }
        }

        String existingValue = translationRepository.findFirstByEntityTypeAndEntityIdAndLanguageCodeAndFieldName(
                        entityType.toUpperCase(),
                        entityId,
                        languageCode.toUpperCase(),
                        fieldName)
                .map(Translation::getFieldValue)
                .orElse(null);

        if (existingValue != null) {
            return existingValue;
        }

        if (entityType != null) {
            String norm = entityType.toUpperCase();
            if (norm.equals("PLANBENEFIT") || norm.equals("SUBSCRIPTIONPACKAGE")) {
                return null;
            }
        }

        // Manual translations only: AZ lives on its own entity table, EN/RU live in
        // the translations table (admin-provided). No machine translation.
        return null;
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void saveOrUpdateTranslation(String entityType, String entityId, String languageCode, String fieldName, String fieldValue) {
        String normalizedEntityType = entityType.toUpperCase();
        String normalizedLanguageCode = languageCode.toUpperCase();

        log.info("Database Save: entityType={}, entityId={}, languageCode={}, fieldName={}, fieldValue='{}'", 
            normalizedEntityType, entityId, normalizedLanguageCode, fieldName, fieldValue);

        try {
            Translation existing = translationRepository.findFirstByEntityTypeAndEntityIdAndLanguageCodeAndFieldName(
                    normalizedEntityType, entityId, normalizedLanguageCode, fieldName
            ).orElse(null);

            if (existing != null) {
                log.info("Updating existing translation record ID={}", existing.getId());
                existing.setFieldValue(fieldValue);
                translationRepository.saveAndFlush(existing);
            } else {
                log.info("Creating new translation record");
                Translation translation = Translation.builder()
                        .entityType(normalizedEntityType)
                        .entityId(entityId)
                        .languageCode(normalizedLanguageCode)
                        .fieldName(fieldName)
                        .fieldValue(fieldValue)
                        .build();
                translationRepository.saveAndFlush(translation);
            }
        } catch (Exception e) {
            log.warn("Exception during save. Retrying as update: {}", e.getMessage());
            try {
                Translation existing = translationRepository.findFirstByEntityTypeAndEntityIdAndLanguageCodeAndFieldName(
                        normalizedEntityType, entityId, normalizedLanguageCode, fieldName
                ).orElse(null);
                if (existing != null) {
                    existing.setFieldValue(fieldValue);
                    translationRepository.saveAndFlush(existing);
                } else {
                    log.error("Failed to recover or find translation after error: {}", e.getMessage());
                }
            } catch (Exception retryEx) {
                log.error("Retry update failed: {}", retryEx.getMessage());
            }
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deleteByEntityTypeAndEntityId(String entityType, String entityId) {
        if (entityType == null || entityId == null) {
            return;
        }
        String normalizedEntityType = entityType.toUpperCase();
        log.info("Deleting translations for entityType={}, entityId={}", normalizedEntityType, entityId);
        translationRepository.deleteByEntityTypeAndEntityId(normalizedEntityType, entityId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void saveTranslation(String entityType, String entityId, String languageCode, String fieldName, String fieldValue) {
        if (entityType == null || entityId == null || languageCode == null || fieldName == null) {
            return;
        }
        String normalizedEntityType = entityType.toUpperCase();
        String normalizedLanguageCode = languageCode.toUpperCase();
        log.info("Saving translation: entityType={}, entityId={}, languageCode={}, fieldName={}", 
            normalizedEntityType, entityId, normalizedLanguageCode, fieldName);
        saveOrUpdateTranslation(normalizedEntityType, entityId, normalizedLanguageCode, fieldName, fieldValue);
    }
}
