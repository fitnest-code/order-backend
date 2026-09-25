package az.fitnest.order.service.freeze.impl;

import az.fitnest.order.dto.freeze.FreezeTermsAdminRequest;
import az.fitnest.order.dto.freeze.FreezeTermsAdminResponse;
import az.fitnest.order.dto.freeze.FreezeTermsResponse;
import az.fitnest.order.model.entity.FreezeTerms;
import az.fitnest.order.repository.FreezeTermsRepository;
import az.fitnest.order.service.freeze.FreezeTermsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreezeTermsServiceImpl implements FreezeTermsService {

    private final FreezeTermsRepository freezeTermsRepository;

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
        terms.setHtmlContentAz(nullToEmpty(request.getHtmlContentAz()));
        terms.setHtmlContentEn(nullToEmpty(request.getHtmlContentEn()));
        terms.setHtmlContentRu(nullToEmpty(request.getHtmlContentRu()));
        FreezeTerms saved = freezeTermsRepository.save(terms);
        log.info("Saved freeze terms id={}", saved.getId());
        return toAdminResponse(saved);
    }

    @Override
    @Transactional
    public void deleteTerms() {
        freezeTermsRepository.findFirstByOrderByIdAsc().ifPresent(terms -> {
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
        String content = switch (lang) {
            case "EN" -> firstNonBlank(terms.getHtmlContentEn(), terms.getHtmlContentAz(), terms.getHtmlContentRu());
            case "RU" -> firstNonBlank(terms.getHtmlContentRu(), terms.getHtmlContentAz(), terms.getHtmlContentEn());
            default -> firstNonBlank(terms.getHtmlContentAz(), terms.getHtmlContentEn(), terms.getHtmlContentRu());
        };

        return FreezeTermsResponse.builder().htmlContent(content).build();
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
        return FreezeTermsAdminResponse.builder()
                .htmlContentAz(nullToEmpty(terms.getHtmlContentAz()))
                .htmlContentEn(nullToEmpty(terms.getHtmlContentEn()))
                .htmlContentRu(nullToEmpty(terms.getHtmlContentRu()))
                .build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
