package az.fitnest.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BonusDecision {
    private boolean applied;
    private Long campaignId;
    private Integer bonusMonths;
    private Integer totalMonths;
    private Integer paidMonths;
    private String campaignLabel;

    public static BonusDecision notApplied(int paidMonths) {
        return BonusDecision.builder()
                .applied(false)
                .bonusMonths(0)
                .paidMonths(paidMonths)
                .totalMonths(paidMonths)
                .build();
    }
}
