package az.fitnest.order.util;
import lombok.experimental.UtilityClass;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@UtilityClass
public class UserContext {
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal().equals("anonymousUser")) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long) {
            return (Long) principal;
        }
        if (principal instanceof String) {
            try {
                return Long.parseLong((String) principal);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static String getCurrentLanguage() {
        org.springframework.web.context.request.ServletRequestAttributes attributes =
                (org.springframework.web.context.request.ServletRequestAttributes)
                        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();
            String queryLang = request.getParameter("lang");
            if (queryLang != null && !queryLang.isBlank()) {
                String normalized = queryLang.trim().toUpperCase();
                if (normalized.equals("RU") || normalized.equals("EN") || normalized.equals("AZ")) {
                    return normalized;
                }
            }
            String acceptLang = request.getHeader("Accept-Language");
            if (acceptLang != null && !acceptLang.isBlank()) {
                String normalized = acceptLang.trim().toUpperCase();
                if (normalized.startsWith("RU")) return "RU";
                if (normalized.startsWith("EN")) return "EN";
                if (normalized.startsWith("AZ")) return "AZ";
            }
            String lang = request.getHeader("X-User-Language");
            if (lang != null && !lang.isBlank()) {
                String normalized = lang.trim().toUpperCase();
                if (normalized.startsWith("RU")) return "RU";
                if (normalized.startsWith("EN")) return "EN";
                if (normalized.startsWith("AZ")) return "AZ";
            }
        }
        try {
            String localeLang = org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage().toUpperCase();
            if (localeLang.equals("EN") || localeLang.equals("RU") || localeLang.equals("AZ")) {
                return localeLang;
            }
        } catch (Exception ignored) {
        }
        return "AZ";
    }
}
