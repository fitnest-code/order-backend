package az.fitnest.order.event;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Payload of {@code subscription-events}. New home-gym fields are optional so existing
 * consumers that only read {@code userId/action/subscriptionId} keep working.
 */
@Data
@NoArgsConstructor
public class SubscriptionEvent implements Serializable {

    private Long userId;
    private String action;
    private Long subscriptionId;

    private Long gymId;
    private Long previousGymId;
    private String gymName;
    private String endReason;
    private Long campaignId;
    private Map<String, String> title;
    private Map<String, String> body;
    private List<Long> recipients;

    /** F-04: package the subscriber holds, used by the "{gym} is leaving {package}" push. */
    private String packageName;

    /** Planned end of the home gym (F-04/R18), shown in the push. */
    private String endsAt;

    /** Screen the mobile app opens from the push (e.g. the 24h reminder → 1.3). */
    private String deepLink;

    /**
     * End of the current subscription (ISO-8601 offset date-time), used by the
     * {@code HOME_GYM_SELECTED} push to show "unlimited until ...".
     */
    private String validUntil;

    private String amount;
    private String mode;
    private String runMonth;
    private String messageKey;

    public SubscriptionEvent(Long userId, String action, Long subscriptionId) {
        this.userId = userId;
        this.action = action;
        this.subscriptionId = subscriptionId;
    }
}
