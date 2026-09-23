package az.fitnest.order.dto.freeze;

import az.fitnest.order.model.enums.FreezeStatus;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezeRecordDto {
    private Long id;
    private Long subscriptionId;
    private Long userId;
    private int requestedDays;
    private Integer usedDays;
    private Integer returnedDays;
    private LocalDateTime startAt;
    private LocalDateTime planEndAt;
    private LocalDateTime actualEndAt;
    private LocalDateTime expiryBefore;
    private LocalDateTime expiryAfter;
    private FreezeStatus status;
    private String endedBy;
    private LocalDateTime createdAt;
}
