package az.fitnest.order.dto.freeze;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezePreviewResponse {
    private String previewId;
    private Long subscriptionId;
    private int requestedDays;
    private LocalDateTime currentExpiryDate;
    private LocalDateTime newExpiryDate;
    private LocalDateTime freezeStartAt;
    private LocalDateTime freezePlanEndAt;
    private LocalDateTime expiresAt;
}
