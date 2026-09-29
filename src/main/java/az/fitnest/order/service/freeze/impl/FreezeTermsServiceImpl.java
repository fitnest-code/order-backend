package az.fitnest.order.service.freeze.impl;

import az.fitnest.order.dto.freeze.FreezeTermsAdminRequest;
import az.fitnest.order.dto.freeze.FreezeTermsAdminResponse;
import az.fitnest.order.dto.freeze.FreezeTermsResponse;
import az.fitnest.order.model.entity.FreezeTerms;
import az.fitnest.order.repository.FreezeTermsRepository;
import az.fitnest.order.service.freeze.FreezeTermsService;
import az.fitnest.order.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreezeTermsServiceImpl implements FreezeTermsService {

    private final FreezeTermsRepository freezeTermsRepository;
    private final TranslationService translationService;

    @Override
    @Transactional(readOnly = true)
    public FreezeTermsAdminResponse getAdminTerms() {
        return freezeTermsRepository.findFirstByOrderByIdAsc()
                .map(this::toAdminResponse)
                .orElseGet(() -> FreezeTermsAdminResponse.builder()
                        .htmlContentAz("")
                        .htmlContentEn("")
                        .htmlContentRu("")
                        .build());
    }

    @Override
    @Transactional
    public FreezeTermsAdminResponse saveAdminTerms(FreezeTermsAdminRequest request) {
        FreezeTerms terms = freezeTermsRepository.findFirstByOrderByIdAsc().orElseGet(FreezeTerms::new);

        // Save base (AZ) content to entity
        String azContent = request.getHtmlContentAz() != null ? request.getHtmlContentAz() : "";
        terms.setHtmlContent(azContent);
        FreezeTerms saved = freezeTermsRepository.save(terms);
        log.info("Saved freeze terms id={}", saved.getId());

        // Save EN/RU to translations table via TranslationService
        String idStr = saved.getId().toString();
        if (request.getHtmlContentEn() != null && !request.getHtmlContentEn().isBlank()) {
            translationService.autoTranslateAndSave("FREEZE_TERMS", idStr, "html_content", request.getHtmlContentEn());
        }
        if (request.getHtmlContentRu() != null && !request.getHtmlContentRu().isBlank()) {
            translationService.autoTranslateAndSave("FREEZE_TERMS", idStr, "html_content", request.getHtmlContentRu());
        }

        return toAdminResponse(saved);
    }

    @Override
    @Transactional
    public void deleteTerms() {
        freezeTermsRepository.findFirstByOrderByIdAsc().ifPresent(terms -> {
            String idStr = terms.getId().toString();
            // Delete translations first
            translationService.deleteByEntityTypeAndEntityId("FREEZE_TERMS", idStr);
            // Then delete entity
            freezeTermsRepository.delete(terms);
            log.info("Deleted freeze terms id={}", terms.getId());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public FreezeTermsResponse getLocalizedTerms(String language) {
        FreezeTerms terms = freezeTermsRepository.findFirstByOrderByIdAsc().orElse(null);
        if (terms == null) {
            return FreezeTermsResponse.builder().htmlContent("").build();
        }

        String lang = normalizeLanguage(language);
        // Use translation service - handles AZ/EN/RU with fallback automatically
        String content = translationService.getTranslatedValue("FREEZE_TERMS", terms.getId().toString(), "html_content", lang);

        return FreezeTermsResponse.builder().htmlContent(content != null ? content : "").build();
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "AZ";
        }
        String primary = language.split(",")[0].trim();
        if (primary.contains(";")) {
            primary = primary.substring(0, primary.indexOf(';')).trim();
        }
        String lang = primary.toUpperCase();
        if (lang.contains("-")) {
            lang = lang.substring(0, lang.indexOf('-'));
        }
        if (lang.length() > 2) {
            lang = lang.substring(0, 2);
        }
        return switch (lang) {
            case "EN", "RU", "AZ" -> lang;
            default -> "AZ";
        };
    }

    private FreezeTermsAdminResponse toAdminResponse(FreezeTerms terms) {
        String idStr = terms.getId().toString();
        // Read AZ from entity, EN/RU from translations table
        String az = terms.getHtmlContent();
        String en = translationService.getTranslatedValue("FREEZE_TERMS", idStr, "html_content", "EN");
        String ru = translationService.getTranslatedValue("FREEZE_TERMS", idStr, "html_content", "RU");

        return FreezeTermsAdminResponse.builder()
                .htmlContentAz(nullToEmpty(az))
                .htmlContentEn(nullToEmpty(en))
                .htmlContentRu(nullToEmpty(ru))
                .build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}