package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class AdminPromoCampaignNumberItemResponse {

    private String phone; // masked by default
    private String phoneHash;
    private String status; // REDEEMED, REGISTERED, NOT_REGISTERED, REVOKED
    private Boolean registered;
    private Boolean sawPopup;
    private OffsetDateTime redeemedAt;
    private String plan;
    private String paidAmount;
    private Long userId;
}