package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminPromoCampaignPublishCheckResponse {

    private Boolean canPublish;
    private List<String> blockers;
    private List<String> warnings;
    private Long eligibleUsers;
    private Long existingUsersWithPopup;
}