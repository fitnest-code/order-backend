package az.fitnest.order.service.homegym;

import az.fitnest.order.config.HomeGymLimits;
import az.fitnest.order.dto.homegym.HomeGymRulesResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the localized "Əsas zal qaydaları" block (spec §"GET /api/v1/home-gym/rules").
 *
 * <p>All texts live in {@code messages_az/en/ru.properties} under {@code home_gym.rules.*}.
 * The numbers inside them are named placeholders resolved from {@link HomeGymLimits}, the
 * same component the QR decision engine uses, so the rules can never drift away from the
 * enforced limits.
 *
 * <p>The result is user-independent and cached per language for one hour. The cache key
 * contains the four limit values as well, so after a configuration change the next request
 * renders the new numbers even before the TTL expires; {@link EnvironmentChangeEvent} also
 * drops the whole cache explicitly.
 */
@Service
@Slf4j
public class HomeGymRulesService {

    /** Ordered rule keys; the order is part of the mobile contract. */
    static final List<String> ITEM_KEYS = List.of(
            "home_gym",
            "monthly_limit",
            "daily_limit",
            "other_gyms",
            "switch_to_standard",
            "standard_limit",
            "time_slots",
            "freeze",
            "change");

    static final Duration CACHE_TTL = Duration.ofHours(1);

    private static final int MAX_CACHE_ENTRIES = 64;
    private static final List<String> SUPPORTED_LANGUAGES = List.of("az", "en", "ru");
    private static final String DEFAULT_LANGUAGE = "az";
    private static final String ITEM_PREFIX = "home_gym.rules.items.";

    private final MessageSource messageSource;
    private final HomeGymLimits limits;
    private final Clock clock;
    private final Map<CacheKey, CachedRules> cache = new ConcurrentHashMap<>();

    @Autowired
    public HomeGymRulesService(MessageSource messageSource, HomeGymLimits limits) {
        this(messageSource, limits, Clock.systemUTC());
    }

    /** Visible for tests that need to control the one-hour TTL. */
    HomeGymRulesService(MessageSource messageSource, HomeGymLimits limits, Clock clock) {
        this.messageSource = messageSource;
        this.limits = limits;
        this.clock = clock;
    }

    public HomeGymRulesResponse getRules(Locale locale) {
        HomeGymLimits.Snapshot current = limits.snapshot();
        CacheKey key = new CacheKey(language(locale), current);
        Instant now = clock.instant();

        CachedRules cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.rules();
        }

        HomeGymRulesResponse rules = build(key.language(), current);
        evictExpired(now);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new CachedRules(rules, now.plus(CACHE_TTL)));
        return rules;
    }

    /** Spec: "Config dəyişəndə cache təmizlənir". */
    @EventListener(EnvironmentChangeEvent.class)
    public void onConfigurationChange(EnvironmentChangeEvent event) {
        if (!cache.isEmpty()) {
            cache.clear();
            log.info("Home-gym rules cache cleared after configuration change: {}", event.getKeys());
        }
    }

    private HomeGymRulesResponse build(String language, HomeGymLimits.Snapshot snapshot) {
        Locale locale = Locale.forLanguageTag(language);
        StringSubstitutor substitutor = new StringSubstitutor(variables(snapshot), "{", "}");
        substitutor.setEnableSubstitutionInVariables(false);

        List<HomeGymRulesResponse.Item> items = new ArrayList<>(ITEM_KEYS.size());
        for (String itemKey : ITEM_KEYS) {
            items.add(new HomeGymRulesResponse.Item(
                    itemKey,
                    message(ITEM_PREFIX + itemKey + ".title", locale),
                    substitutor.replace(message(ITEM_PREFIX + itemKey + ".text", locale))));
        }

        return new HomeGymRulesResponse(
                substitutor.replace(message("home_gym.rules.title", locale)),
                substitutor.replace(message("home_gym.rules.toggle_label", locale)),
                new HomeGymRulesResponse.Limits(
                        snapshot.homeGymMonthlyLimit(),
                        snapshot.homeGymDailyLimit(),
                        snapshot.standardMonthlyLimit(),
                        snapshot.otherGymsLockThreshold()),
                List.copyOf(items));
    }

    private Map<String, String> variables(HomeGymLimits.Snapshot snapshot) {
        Map<String, String> variables = new HashMap<>(8);
        variables.put("homeGymMonthlyLimit", Integer.toString(snapshot.homeGymMonthlyLimit()));
        variables.put("homeGymDailyLimit", Integer.toString(snapshot.homeGymDailyLimit()));
        variables.put("standardMonthlyLimit", Integer.toString(snapshot.standardMonthlyLimit()));
        variables.put("otherGymsLockThreshold", Integer.toString(snapshot.otherGymsLockThreshold()));
        return variables;
    }

    private String message(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }

    /** Unsupported or missing languages fall back to AZ, as required by the contract. */
    private String language(Locale locale) {
        if (locale == null || locale.getLanguage() == null) {
            return DEFAULT_LANGUAGE;
        }
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        return SUPPORTED_LANGUAGES.contains(language) ? language : DEFAULT_LANGUAGE;
    }

    private void evictExpired(Instant now) {
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record CacheKey(String language, HomeGymLimits.Snapshot limits) {
    }

    private record CachedRules(HomeGymRulesResponse rules, Instant expiresAt) {
    }
}
