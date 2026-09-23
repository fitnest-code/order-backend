package az.fitnest.order.dto.freeze;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezeEligibilityResponse {
    private boolean eligible;
    private String reason;
    private int totalDays;
    private int consumedDays;
    private int reservedDays;
    private int availableDays;
    private boolean isCurrentlyFrozen;
    private Long activeFreezeId;
}
