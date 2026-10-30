package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromoCampaignPublishCheckResponse {

    private Boolean canPublish;
    private List<String> blockers;
    private List<String> warnings;
    private Long eligibleUsers;
    private Long existingUsersWithPopup;
}