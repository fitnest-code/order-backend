package az.fitnest.order.service.impl;

import az.fitnest.order.model.entity.Translation;
import az.fitnest.order.repository.TranslationRepository;
import az.fitnest.order.service.TranslationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;

@Service
@RequiredArgsConstructor
public class TranslationServiceImpl implements TranslationService {

    private final TranslationRepository translationRepository;
    private static final Logger log = LoggerFactory.getLogger(TranslationServiceImpl.class);
    // Reused across translate calls: avoids per-call socket churn and mapper setup cost.
    private static final RestTemplate GOOGLE_REST_TEMPLATE = buildGoogleRestTemplate();
    private static final ObjectMapper GOOGLE_RESPONSE_MAPPER = new ObjectMapper();

    private static RestTemplate buildGoogleRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().add(0, new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
        return restTemplate;
    }

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private TranslationServiceImpl self;

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

        try {
            String originalValueAz = null;
            if (entityType != null) {
                String normType = entityType.toUpperCase();
                if (normType.equals("PLANBENEFIT")) {
                    int idx = entityId.indexOf("_");
                    if (idx != -1) {
                        originalValueAz = entityId.substring(idx + 1);
                    }
                } else {
                    Class<?> entityClass = translationEntityResolver.getEntityClass(entityType);
                    if (entityClass != null) {
                        Object entity = null;
                        try {
                            Long longId = Long.parseLong(entityId);
                            entity = entityManager.find(entityClass, longId);
                        } catch (NumberFormatException e) {
                            entity = entityManager.find(entityClass, entityId);
                        }
                        if (entity != null) {
                            originalValueAz = translationEntityResolver.extractFieldValue(entity, fieldName);
                        }
                    }
                }
            }

            if (originalValueAz != null && !originalValueAz.trim().isEmpty()) {
                String translatedValue = translateText(originalValueAz, languageCode.toLowerCase());
                if (translatedValue != null && !translatedValue.trim().isEmpty()) {
                    self.saveOrUpdateTranslation(entityType, entityId, languageCode, fieldName, translatedValue);
                    return translatedValue;
                }
            }
        } catch (Exception e) {
            log.error("Soft fallback translation failed for entityType={}, entityId={}, fieldName={}, lang={}",
                    entityType, entityId, fieldName, languageCode, e);
        }

        return null;
    }

    @Override
    @Async
    public void autoTranslateAndSave(String entityType, String entityId, String fieldName, String originalValueAz) {
        if (entityType != null) {
            String norm = entityType.toUpperCase();
            if (norm.equals("PLANBENEFIT") || norm.equals("SUBSCRIPTIONPACKAGE")) {
                return;
            }
        }
        if (originalValueAz == null || originalValueAz.trim().isEmpty()) {
            log.warn("Auto-translation skipped: originalValueAz is null or empty for entityType={}, entityId={}, fieldName={}", 
                entityType, entityId, fieldName);
            return;
        }

        log.info("Starting auto-translation process for entityType={}, entityId={}, fieldName={}, originalValueAz='{}'", 
            entityType, entityId, fieldName, originalValueAz);

        // Persist the original AZ value so getTranslatedValue("AZ") returns it
        // instead of falling back to hardcoded defaults.
        saveOrUpdateTranslation(entityType, entityId, "AZ", fieldName, originalValueAz);

        // Translate to EN
        String enValue = translateText(originalValueAz, "en");
        if (enValue != null && !enValue.trim().isEmpty()) {
            log.info("Auto-translated [AZ -> EN] success. Value: '{}'", enValue);
            saveOrUpdateTranslation(entityType, entityId, "EN", fieldName, enValue);
        } else {
            log.warn("Auto-translation [AZ -> EN] returned empty or null value. Using fallback: '{}'", originalValueAz);
            saveOrUpdateTranslation(entityType, entityId, "EN", fieldName, originalValueAz);
        }

        // Translate to RU
        String ruValue = translateText(originalValueAz, "ru");
        if (ruValue != null && !ruValue.trim().isEmpty()) {
            log.info("Auto-translated [AZ -> RU] success. Value: '{}'", ruValue);
            saveOrUpdateTranslation(entityType, entityId, "RU", fieldName, ruValue);
        } else {
            log.warn("Auto-translation [AZ -> RU] returned empty or null value. Using fallback: '{}'", originalValueAz);
            saveOrUpdateTranslation(entityType, entityId, "RU", fieldName, originalValueAz);
        }
    }

    private String translateText(String text, String targetLanguage) {
        try {
            String googleTranslated = translateWithGoogle(text, targetLanguage);
            if (googleTranslated != null && !googleTranslated.trim().isEmpty()) {
                log.info("Translation successful using Google Translate [AZ -> {}]: '{}' -> '{}'", 
                    targetLanguage.toUpperCase(), text, googleTranslated);
                return googleTranslated;
            }
        } catch (Exception e) {
            log.error("Google Translate failed. Error: {}", e.getMessage());
        }
        return null;
    }

    private String translateWithGoogle(String text, String targetLanguage) {
        try {
            URI uri = UriComponentsBuilder
                .fromUriString("https://translate.googleapis.com/translate_a/single")
                .queryParam("client", "gtx")
                .queryParam("sl", "az")
                .queryParam("tl", targetLanguage.toLowerCase())
                .queryParam("dt", "t")
                .queryParam("q", text)
                .build()
                .toUri();

            log.info("Google Translate Request [AZ -> {}]: '{}'", targetLanguage.toUpperCase(), text);
            String response = GOOGLE_REST_TEMPLATE.getForObject(uri, String.class);
            if (response != null) {
                JsonNode rootNode = GOOGLE_RESPONSE_MAPPER.readTree(response);
                if (rootNode.isArray() && rootNode.size() > 0) {
                    JsonNode firstArray = rootNode.get(0);
                    if (firstArray.isArray() && firstArray.size() > 0) {
                        JsonNode translationPair = firstArray.get(0);
                        if (translationPair.isArray() && translationPair.size() > 0) {
                            return translationPair.get(0).asText();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Google Translation API failed for text '{}' to '{}': {}", text, targetLanguage, e.getMessage());
        }
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
