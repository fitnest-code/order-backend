package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.pricingcampaign.dto.ActivePricingCampaignDto;
import az.fitnest.order.pricingcampaign.dto.CampaignLandingDto;
import az.fitnest.order.pricingcampaign.dto.CampaignLandingDto.Cta;
import az.fitnest.order.pricingcampaign.dto.CampaignLandingDto.Header;
import az.fitnest.order.pricingcampaign.dto.CampaignLandingDto.Option;
import az.fitnest.order.pricingcampaign.dto.CampaignLandingDto.PackagePrice;
import az.fitnest.order.pricingcampaign.dto.PricingDto;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignDiscount;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignLandingI18n;
import az.fitnest.order.pricingcampaign.repository.PricingCampaignLandingI18nRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.service.TranslationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the ready-to-bind Black Friday intro screen so mobile does not stitch /active + v4 packages
 * and compose strings itself. Pricing deliberately reuses {@link PricingCampaignService#computePricing}
 * (the same math as v4 catalog and checkout preview).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingCampaignLandingService {

    static final String FALLBACK_LOCALE = "az";
    // Campaign windows are defined in Baku time; render dates there regardless of server zone.
    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final PricingCampaignService campaignService;
    private final PricingCampaignLandingI18nRepository landingRepository;
    private final SubscriptionPackageRepository packageRepository;
    private final TranslationService translationService;
    private final ObjectMapper objectMapper;

    /** Empty when no campaign is active (controller maps that to a 200 null body, like /active). */
    @Transactional(readOnly = true)
    public Optional<CampaignLandingDto> findActiveLanding(Long userId, String language) {
        String locale = normalizeLocale(language);
        return campaignService.findActiveCampaign().map(c -> build(c, userId, locale));
    }

    private CampaignLandingDto build(PricingCampaign c, Long userId, String locale) {
        ActivePricingCampaignDto state = campaignService.describe(c, userId);
        // DB rows are preferred (ops-editable). If Flyway/seed never ran — DEV once had an empty
        // Hibernate-created table — fall back to built-in design copy so mobile never gets header:{}.
        PricingCampaignLandingI18n copy = findCopy(c, locale).orElseGet(() -> {
            log.warn("pricing_campaign_landing_i18n missing for campaign={} locale={}; using built-in copy",
                    c.getCode(), locale);
            return builtInCopy(c.getId(), locale);
        });
        String resolvedLocale = copy.getLocale();
        Map<String, String> vars = Map.of(
                "maxPercent", String.valueOf(c.getMaxPercent()),
                "startsAt", formatDate(c.getStartsAt()),
                "endsAt", formatDate(c.getEndsAt()),
                "endsAtTime", c.getEndsAt().atZoneSameInstant(BAKU).format(TIME));

        Header header = new Header(render(copy.getTitle(), vars), render(copy.getHeadline(), vars),
                render(copy.getSubtitle(), vars));
        List<String> terms = parseTerms(copy.getTermsJson()).stream().map(t -> render(t, vars)).toList();
        Cta cta = new Cta(render(copy.getCtaTitle(), vars));

        return new CampaignLandingDto(c.getId(), c.getCode(), resolvedLocale, c.getStartsAt(), c.getEndsAt(),
                c.getMaxPercent(), state.eligible(), state.usedByUser(), header,
                buildOptions(c, copy.getOptionLabelTemplate(), vars, locale),
                terms, cta);
    }

    /** Requested locale first, AZ as the guaranteed-seeded fallback. */
    private Optional<PricingCampaignLandingI18n> findCopy(PricingCampaign c, String locale) {
        Optional<PricingCampaignLandingI18n> exact = landingRepository.findByCampaignIdAndLocale(c.getId(), locale);
        if (exact.isPresent() || FALLBACK_LOCALE.equals(locale)) return exact;
        return landingRepository.findByCampaignIdAndLocale(c.getId(), FALLBACK_LOCALE);
    }

    /** Design-aligned defaults when the i18n table has no row (seed skipped / Hibernate-only schema). */
    static PricingCampaignLandingI18n builtInCopy(UUID campaignId, String locale) {
        String loc = normalizeLocale(locale);
        PricingCampaignLandingI18n i = new PricingCampaignLandingI18n();
        i.setCampaignId(campaignId);
        i.setLocale(loc);
        switch (loc) {
            case "en" -> {
                i.setTitle("Black Friday");
                i.setHeadline("Up to {maxPercent}% off!");
                i.setSubtitle("Get 3, 6 and 12 month memberships at the Black Friday price.");
                i.setOptionLabelTemplate("Get {months} months - {percent} % off");
                i.setCtaTitle("Choose your plan");
                i.setTermsJson("[\"Campaign is valid {startsAt}–{endsAt} ({endsAt} {endsAtTime}, Baku time).\","
                        + "\"1-month subscriptions are not included.\","
                        + "\"Each user can use the offer once.\","
                        + "\"FitCoin is applied to the discounted amount.\","
                        + "\"The discount is applied automatically at payment.\"]");
            }
            case "ru" -> {
                i.setTitle("Чёрная пятница");
                i.setHeadline("Скидки до {maxPercent}%!");
                i.setSubtitle("Приобретайте абонементы на 3, 6 и 12 месяцев по цене Чёрной пятницы.");
                i.setOptionLabelTemplate("Купить на {months} мес. - скидка {percent} %");
                i.setCtaTitle("Выбрать план");
                i.setTermsJson("[\"Акция действует {startsAt}–{endsAt} ({endsAt} {endsAtTime}, время Баку).\","
                        + "\"Месячная подписка в акцию не входит.\","
                        + "\"Каждый пользователь может воспользоваться один раз.\","
                        + "\"FitCoin применяется к сумме со скидкой.\","
                        + "\"Скидка применяется автоматически при оплате.\"]");
            }
            default -> {
                i.setLocale(FALLBACK_LOCALE);
                i.setTitle("Qara Cümə");
                i.setHeadline("{maxPercent}%-dək endirim!");
                i.setSubtitle("3, 6 və 12 aylıq abunəlikləri Qara Cümə qiyməti ilə al.");
                i.setOptionLabelTemplate("{months} aylıq al - {percent} % endirim");
                i.setCtaTitle("Planını seç");
                i.setTermsJson("[\"Kampaniya {startsAt}–{endsAt} ({endsAt} {endsAtTime}, Bakı vaxtı) keçərlidir.\","
                        + "\"1 aylıq abunəlik kampaniyaya daxil deyil.\","
                        + "\"Hər istifadəçi 1 dəfə yararlana bilər.\","
                        + "\"FitCoin endirimli məbləğə tətbiq olunur.\","
                        + "\"Endirim ödəniş zamanı avtomatik tətbiq olunur.\"]");
            }
        }
        return i;
    }

    private List<Option> buildOptions(PricingCampaign c, String labelTemplate, Map<String, String> vars,
                                      String locale) {
        List<SubscriptionPackage> packages = packageRepository.findByIsActiveTrueOrdered();
        return c.getDiscounts().stream()
                .filter(d -> d.getMonths() != null && d.getMonths() > 0
                        && d.getPercent() != null && d.getPercent().signum() > 0)
                .sorted(Comparator.comparing(PricingCampaignDiscount::getMonths))
                .map(d -> toOption(d, c, packages, labelTemplate, vars, locale))
                // A tier nobody sells (no active package has that month option) would be a dead row in the UI.
                .filter(o -> !o.packages().isEmpty())
                .toList();
    }

    private Option toOption(PricingCampaignDiscount d, PricingCampaign c, List<SubscriptionPackage> packages,
                            String labelTemplate, Map<String, String> vars, String locale) {
        int months = d.getMonths();
        List<PackagePrice> prices = packages.stream()
                .flatMap(p -> p.getOptions().stream()
                        .filter(o -> !Boolean.FALSE.equals(o.getIsActive()) && o.getPriceStandard() != null
                                && o.getDurationMonths() != null && o.getDurationMonths() == months)
                        .sorted(Comparator.comparing(PackageOption::getId))
                        .map(o -> toPackagePrice(p, o, c, months, locale)))
                .toList();
        String percent = plain(d.getPercent());
        String label = labelTemplate == null ? null : render(labelTemplate, merge(vars,
                Map.of("months", String.valueOf(months), "percent", percent)));
        // new BigDecimal(plain) avoids Jackson emitting "2E+1" for a stripped 20.00.
        return new Option(months, new BigDecimal(percent), label, prices);
    }

    private PackagePrice toPackagePrice(SubscriptionPackage p, PackageOption o, PricingCampaign c, int months,
                                        String locale) {
        // Priced against the campaign itself (not "applicable for user"): the landing advertises the BF price;
        // eligible/usedByUser tell mobile whether it is payable, and checkout preview stays authoritative.
        PricingDto pricing = campaignService.computePricing(o.getPriceStandard(), months, c);
        return new PackagePrice(p.getId(), o.getId(), localizedName(p, locale), pricing.listPrice(),
                pricing.finalPrice(), pricing.savedAmount(), p.getCurrency() != null ? p.getCurrency() : "AZN",
                pricing.discountPercent(), pricing.campaignApplied());
    }

    private String localizedName(SubscriptionPackage p, String locale) {
        String translated = translationService.getTranslatedValue(
                "SUBSCRIPTIONPACKAGE", p.getId().toString(), "name", locale.toUpperCase());
        return translated == null || translated.isBlank() ? p.getName() : translated;
    }

    private List<String> parseTerms(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // Bad ops-edited JSON must not take the whole screen down; log so it gets fixed.
            log.warn("Invalid terms_json on pricing_campaign_landing_i18n: {}", e.getMessage());
            return List.of();
        }
    }

    static String render(String template, Map<String, String> vars) {
        if (template == null) return null;
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>(a);
        m.putAll(b);
        return m;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String formatDate(OffsetDateTime t) {
        return t.atZoneSameInstant(BAKU).format(DATE);
    }

    /** UserContext yields AZ/EN/RU; anything else falls back to AZ. */
    static String normalizeLocale(String language) {
        String l = language == null ? "" : language.trim().toLowerCase();
        return l.equals("en") || l.equals("ru") ? l : FALLBACK_LOCALE;
    }
}
