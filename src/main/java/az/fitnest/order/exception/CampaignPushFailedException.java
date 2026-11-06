package az.fitnest.order.exception;

import org.springframework.http.HttpStatus;

/** notifications-backend rejected or did not answer the campaign broadcast. */
public class CampaignPushFailedException extends BaseException {

    public CampaignPushFailedException() {
        super("Campaign push could not be delivered", "CAMPAIGN_PUSH_FAILED", HttpStatus.BAD_GATEWAY);
    }
}
