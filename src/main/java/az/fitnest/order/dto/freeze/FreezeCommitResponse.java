package az.fitnest.order.dto.freeze;

import az.fitnest.order.model.enums.FreezeStatus;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezeCommitResponse {
    private Long freezeId;
    private Long subscriptionId;
    private int requestedDays;
    private LocalDateTime startAt;
    private LocalDateTime planEndAt;
    private LocalDateTime expiryBefore;
    private LocalDateTime expiryAfter;
    private FreezeStatus status;
}
