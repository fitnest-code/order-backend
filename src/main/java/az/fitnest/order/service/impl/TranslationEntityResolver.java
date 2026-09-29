package az.fitnest.order.service.impl;

import org.springframework.stereotype.Component;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TranslationEntityResolver {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TranslationEntityResolver.class);
    private final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

    public Class<?> getEntityClass(String entityType) {
        if (entityType == null) return null;
        switch (entityType.toUpperCase()) {
            case "SUBSCRIPTIONPACKAGE": return az.fitnest.order.model.entity.SubscriptionPackage.class;
            case "CAMPAIGN": return az.fitnest.order.model.entity.Campaign.class;
            case "CAMPAIGNOFFER": return az.fitnest.order.model.entity.CampaignOffer.class;
            case "CAMPAIGNTERM": return az.fitnest.order.model.entity.CampaignTerm.class;
            case "FREEZE_TERMS": return az.fitnest.order.model.entity.FreezeTerms.class;
            default: return null;
        }
    }

    public String extractFieldValue(Object entity, String fieldName) {
        if (entity == null || fieldName == null) return null;
        try {
            // Try exact field name first
            Field field = getCachedField(entity.getClass(), fieldName);
            if (field != null) {
                return getFieldValue(entity, field);
            }
            
            // Try camelCase conversion (e.g., "html_content" -> "htmlContent")
            String camelCaseFieldName = toCamelCase(fieldName);
            if (!camelCaseFieldName.equals(fieldName)) {
                field = getCachedField(entity.getClass(), camelCaseFieldName);
                if (field != null) {
                    return getFieldValue(entity, field);
                }
            }
            
            // Try searching all declared fields
            for (Field f : entity.getClass().getDeclaredFields()) {
                if (!f.getType().isPrimitive() && !f.getType().getName().startsWith("java.lang") && !f.getType().isEnum()) {
                    f.setAccessible(true);
                    Object child = f.get(entity);
                    if (child != null) {
                        Field childField = getCachedField(child.getClass(), fieldName);
                        if (childField != null) {
                            return getFieldValue(child, childField);
                        }
                        // Also try camelCase on child
                        if (!camelCaseFieldName.equals(fieldName)) {
                            childField = getCachedField(child.getClass(), camelCaseFieldName);
                            if (childField != null) {
                                return getFieldValue(child, childField);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract field value for fieldName: {}", fieldName, e);
        }
        return null;
    }
    
    private String toCamelCase(String snakeCase) {
        StringBuilder result = new StringBuilder();
        boolean nextUpper = false;
        for (char c : snakeCase.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                result.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private Field getCachedField(Class<?> clazz, String fieldName) {
        String key = clazz.getName() + "#" + fieldName;
        return fieldCache.computeIfAbsent(key, k -> {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Field f = current.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            return null;
        });
    }

    private String getFieldValue(Object target, Field field) throws IllegalAccessException {
        Object val = field.get(target);
        return val != null ? val.toString() : null;
    }
}
