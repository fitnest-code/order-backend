package az.fitnest.order.service;

public interface TranslationService {
    String getTranslatedValue(String entityType, String entityId, String fieldName, String languageCode);
    void autoTranslateAndSave(String entityType, String entityId, String fieldName, String originalValueAz);
}
