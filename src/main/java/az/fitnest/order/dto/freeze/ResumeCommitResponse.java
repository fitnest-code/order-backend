package az.fitnest.order.dto.freeze;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResumeCommitResponse {
    private Long freezeId;
    private Long subscriptionId;
    private int requestedDays;
    private int usedDays;
    private int returnedDays;
    private LocalDateTime startAt;
    private LocalDateTime actualEndAt;
    private LocalDateTime expiryBefore;
    private LocalDateTime newExpiryDate;
    private boolean isPlanEndReached;
}
