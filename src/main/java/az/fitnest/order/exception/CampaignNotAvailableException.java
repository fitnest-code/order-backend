package az.fitnest.order.exception;

import org.springframework.http.HttpStatus;

/** Ended, disabled, or not-yet-started bonus campaign. Mobile shows the ended-campaign fallback. */
public class CampaignNotAvailableException extends BaseException {

    public CampaignNotAvailableException() {
        super("This campaign has ended", "CAMPAIGN_NOT_AVAILABLE", HttpStatus.GONE);
    }
}
