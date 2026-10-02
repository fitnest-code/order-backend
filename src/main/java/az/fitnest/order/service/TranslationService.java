package az.fitnest.order.service;

public interface TranslationService {
    String getTranslatedValue(String entityType, String entityId, String fieldName, String languageCode);
    void saveTranslation(String entityType, String entityId, String languageCode, String fieldName, String fieldValue);
    void deleteByEntityTypeAndEntityId(String entityType, String entityId);
}
