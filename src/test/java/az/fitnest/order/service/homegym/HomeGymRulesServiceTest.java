package az.fitnest.order.service.homegym;

import az.fitnest.order.config.HomeGymLimits;
import az.fitnest.order.dto.homegym.HomeGymRulesResponse;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-R1..T-R7 for {@code GET /api/v1/home-gym/rules}.
 */
class HomeGymRulesServiceTest {

    private static final List<String> LANGUAGES = List.of("az", "en", "ru");
    private static final List<String> FORBIDDEN_WORDS = List.of("limitsiz", "unlimited", "безлимитно");

    private final MockEnvironment environment = new MockEnvironment();
    private final HomeGymLimits limits = new HomeGymLimits(environment);
    private final ResourceBundleMessageSource messageSource = messageSource();
    private final HomeGymRulesService service = new HomeGymRulesService(messageSource, limits);

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void returnsTheNineRulesInTheContractOrderForEveryLanguage() { // T-R1
        for (String language : LANGUAGES) {
            HomeGymRulesResponse rules = service.getRules(Locale.forLanguageTag(language));

            assertEquals(HomeGymRulesService.ITEM_KEYS.size(), rules.items().size());
            assertEquals(HomeGymRulesService.ITEM_KEYS,
                    rules.items().stream().map(HomeGymRulesResponse.Item::key).toList());
            assertFalse(rules.title().isBlank());
            assertFalse(rules.toggleLabel().isBlank());
            rules.items().forEach(item -> {
                assertFalse(item.title().isBlank(), language + "/" + item.key() + " title");
                assertFalse(item.text().isBlank(), language + "/" + item.key() + " text");
            });
        }
    }

    @Test
    void fillsTheNumbersFromConfiguration() { // T-R2
        HomeGymRulesResponse rules = service.getRules(Locale.forLanguageTag("az"));

        assertEquals(new HomeGymRulesResponse.Limits(30, 1, 12, 12), rules.limits());
        assertTrue(text(rules, "monthly_limit").contains("30"));
        assertTrue(text(rules, "daily_limit").contains("1"));
        assertTrue(text(rules, "other_gyms").contains("12"));
        assertTrue(text(rules, "standard_limit").contains("12"));
    }

    @Test
    void eachLanguageHasItsOwnLocalizedText() { // T-R1
        Set<String> titles = new HashSet<>();
        for (String language : LANGUAGES) {
            HomeGymRulesResponse rules = service.getRules(Locale.forLanguageTag(language));
            titles.add(rules.title());
            assertFalse(rules.title().startsWith("home_gym.rules"), language + " uses the raw key");
        }
        assertEquals(LANGUAGES.size(), titles.size());
    }

    @Test
    void unsupportedLanguageFallsBackToAzerbaijani() { // T-R3
        HomeGymRulesResponse azerbaijani = service.getRules(Locale.forLanguageTag("az"));

        assertEquals(azerbaijani, service.getRules(Locale.GERMAN));
        assertEquals(azerbaijani, service.getRules(null));
        assertEquals(azerbaijani, service.getRules(Locale.forLanguageTag("az-AZ")));
    }

    @Test
    void configurationChangeIsReflectedInTextAndLimits() { // T-R4
        HomeGymRulesResponse before = service.getRules(Locale.forLanguageTag("az"));
        assertTrue(text(before, "monthly_limit").contains("30"));

        environment.setProperty(HomeGymLimits.MONTHLY_LIMIT_PROPERTY, "45");
        environment.setProperty(HomeGymLimits.OTHER_GYMS_LOCK_THRESHOLD_PROPERTY, "20");

        HomeGymRulesResponse after = service.getRules(Locale.forLanguageTag("az"));

        assertEquals(45, after.limits().homeGymMonthlyLimit());
        assertEquals(20, after.limits().otherGymsLockThreshold());
        assertTrue(text(after, "monthly_limit").contains("45"));
        assertFalse(text(after, "monthly_limit").contains("30"));
        assertTrue(text(after, "other_gyms").contains("20"));
        assertNotEquals(before, after);
    }

    @Test
    void configurationEventClearsTheCache() { // T-R4
        HomeGymRulesResponse first = service.getRules(Locale.forLanguageTag("az"));

        service.onConfigurationChange(new EnvironmentChangeEvent(Set.of(HomeGymLimits.MONTHLY_LIMIT_PROPERTY)));

        HomeGymRulesResponse second = service.getRules(Locale.forLanguageTag("az"));
        assertNotSame(first, second);
        assertEquals(first, second);
    }

    @Test
    void neverLeavesAnUnsubstitutedPlaceholder() { // T-R5
        for (String language : LANGUAGES) {
            HomeGymRulesResponse rules = service.getRules(Locale.forLanguageTag(language));
            List<String> values = new ArrayList<>();
            values.add(rules.title());
            values.add(rules.toggleLabel());
            rules.items().forEach(item -> {
                values.add(item.title());
                values.add(item.text());
            });
            values.forEach(value ->
                    assertFalse(value.contains("{") || value.contains("}"),
                            language + " contains an unresolved placeholder: " + value));
        }
    }

    @Test
    void neverUsesForbiddenWording() { // T-R6
        for (String language : LANGUAGES) {
            HomeGymRulesResponse rules = service.getRules(Locale.forLanguageTag(language));
            List<String> values = new ArrayList<>();
            values.add(rules.title());
            values.add(rules.toggleLabel());
            rules.items().forEach(item -> {
                values.add(item.title());
                values.add(item.text());
            });
            for (String value : values) {
                String normalized = value.toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_WORDS) {
                    assertFalse(normalized.contains(forbidden),
                            language + " uses forbidden wording '" + forbidden + "': " + value);
                }
            }
        }
    }

    @Test
    void cachesEachLanguageForOneHour() { // T-R7
        MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        HomeGymRulesService cachedService = new HomeGymRulesService(messageSource, limits, clock);
        Locale az = Locale.forLanguageTag("az");

        HomeGymRulesResponse first = cachedService.getRules(az);
        assertSame(first, cachedService.getRules(az));

        clock.advance(Duration.ofMinutes(59));
        assertSame(first, cachedService.getRules(az));

        clock.advance(Duration.ofMinutes(2));
        HomeGymRulesResponse refreshed = cachedService.getRules(az);
        assertNotSame(first, refreshed);
        assertEquals(first, refreshed);
    }

    @Test
    void invalidConfiguredValueFallsBackToTheDefault() {
        environment.setProperty(HomeGymLimits.MONTHLY_LIMIT_PROPERTY, "not-a-number");
        assertEquals(HomeGymLimits.DEFAULT_MONTHLY_LIMIT, limits.homeGymMonthlyLimit());

        environment.setProperty(HomeGymLimits.DAILY_LIMIT_PROPERTY, "-3");
        assertEquals(HomeGymLimits.DEFAULT_DAILY_LIMIT, limits.homeGymDailyLimit());

        environment.setProperty(HomeGymLimits.STANDARD_MONTHLY_LIMIT_PROPERTY, "40");
        assertEquals(40, limits.standardMonthlyLimit());
    }

    private String text(HomeGymRulesResponse rules, String key) {
        return rules.items().stream()
                .filter(item -> item.key().equals(key))
                .findFirst()
                .orElseThrow()
                .text();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
