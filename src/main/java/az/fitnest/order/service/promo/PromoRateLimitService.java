package az.fitnest.order.service.promo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * NFR-2 rate limiting for promo endpoints:
 *  - 30 quote calls per user per minute
 *  - 10 failed promo validations per user per hour
 */
@Service
@Slf4j
public class PromoRateLimitService {

    private final StringRedisTemplate redisTemplate;

    @Value("${promo.rate-limit.quote-per-minute:30}")
    private int quotePerMinute;

    @Value("${promo.rate-limit.failed-validations-per-hour:10}")
    private int failedValidationsPerHour;

    public PromoRateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryConsumeQuote(Long userId) {
        String key = "promo:quote:1m:" + userId;
        return allow(key, quotePerMinute, Duration.ofMinutes(1));
    }

    public boolean tryConsumeFailedValidation(Long userId) {
        String key = "promo:failed:1h:" + userId;
        return allow(key, failedValidationsPerHour, Duration.ofHours(1));
    }

    public void resetFailedValidations(Long userId) {
        redisTemplate.delete("promo:failed:1h:" + userId);
    }

    public long remainingQuote(Long userId) {
        String key = "promo:quote:1m:" + userId;
        String v = redisTemplate.opsForValue().get(key);
        return v == null ? quotePerMinute : Math.max(0, quotePerMinute - Long.parseLong(v));
    }

    private boolean allow(String key, int limit, Duration ttl) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) return true;
            if (count == 1L) {
                redisTemplate.expire(key, ttl);
            }
            return count <= limit;
        } catch (Exception e) {
            log.warn("Rate limit check failed for key={}: {}", key, e.getMessage());
            // Fail open: never block a legitimate user because Redis is down
            return true;
        }
    }
}
