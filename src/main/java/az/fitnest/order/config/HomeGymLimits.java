package az.fitnest.order.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the home-gym visit limits (spec §"Konfiqurasiya").
 *
 * <p>The QR decision engine (order-backend) and the {@code GET /api/v1/home-gym/rules}
 * texts must always agree, so both read the values through this component instead of
 * keeping constants. Values are resolved from {@link Environment} on every read, which
 * means a runtime configuration change (Vault / {@code /actuator/env} + refresh) applies
 * to the check-in logic and to the rendered rules at the same time.
 *
 * <p>Recognised keys (environment variable form in brackets):
 * <ul>
 *     <li>{@code home_gym.monthly_limit} [{@code HOME_GYM_MONTHLY_LIMIT}] - default 30</li>
 *     <li>{@code home_gym.daily_limit} [{@code HOME_GYM_DAILY_LIMIT}] - default 1</li>
 *     <li>{@code standard.monthly_limit} [{@code STANDARD_MONTHLY_LIMIT}] - default 12</li>
 *     <li>{@code home_gym.other_gyms_lock_threshold}
 *     [{@code HOME_GYM_OTHER_GYMS_LOCK_THRESHOLD}] - default 12</li>
 * </ul>
 *
 * <p>Missing, non-numeric or non-positive values fall back to the default and log a
 * warning; a bad configuration must never block QR entry for every subscriber.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HomeGymLimits {

    public static final String MONTHLY_LIMIT_PROPERTY = "home_gym.monthly_limit";
    public static final String DAILY_LIMIT_PROPERTY = "home_gym.daily_limit";
    public static final String STANDARD_MONTHLY_LIMIT_PROPERTY = "standard.monthly_limit";
    public static final String OTHER_GYMS_LOCK_THRESHOLD_PROPERTY = "home_gym.other_gyms_lock_threshold";

    public static final int DEFAULT_MONTHLY_LIMIT = 30;
    public static final int DEFAULT_DAILY_LIMIT = 1;
    public static final int DEFAULT_STANDARD_MONTHLY_LIMIT = 12;
    public static final int DEFAULT_OTHER_GYMS_LOCK_THRESHOLD = 12;

    private final Environment environment;

    /** Consistent view of all four limits, so one request never mixes two configurations. */
    public Snapshot snapshot() {
        return new Snapshot(
                homeGymMonthlyLimit(),
                homeGymDailyLimit(),
                standardMonthlyLimit(),
                otherGymsLockThreshold());
    }

    /** R21: maximum home-gym visits in one subscription month. */
    public int homeGymMonthlyLimit() {
        return positiveInt(MONTHLY_LIMIT_PROPERTY, DEFAULT_MONTHLY_LIMIT);
    }

    /** R21: maximum home-gym visits in one Baku calendar day. */
    public int homeGymDailyLimit() {
        return positiveInt(DAILY_LIMIT_PROPERTY, DEFAULT_DAILY_LIMIT);
    }

    /** R7: visits allowed in a subscription month when the subscription is in STANDARD mode. */
    public int standardMonthlyLimit() {
        return positiveInt(STANDARD_MONTHLY_LIMIT_PROPERTY, DEFAULT_STANDARD_MONTHLY_LIMIT);
    }

    /** R6: other gyms are locked once the home gym reaches this many visits in the month. */
    public int otherGymsLockThreshold() {
        return positiveInt(OTHER_GYMS_LOCK_THRESHOLD_PROPERTY, DEFAULT_OTHER_GYMS_LOCK_THRESHOLD);
    }

    private int positiveInt(String key, int fallback) {
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
            log.warn("Ignoring non-positive home-gym limit {}={}; using default {}", key, value, fallback);
        } catch (NumberFormatException ex) {
            log.warn("Ignoring non-numeric home-gym limit {}={}; using default {}", key, raw, fallback);
        }
        return fallback;
    }

    /** Immutable view of the four limits used by one request or one rules response. */
    public record Snapshot(
            int homeGymMonthlyLimit,
            int homeGymDailyLimit,
            int standardMonthlyLimit,
            int otherGymsLockThreshold) {
    }
}
