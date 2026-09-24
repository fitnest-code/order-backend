package az.fitnest.order.dto.freeze;

import java.time.LocalDateTime;

public record ResumePreviewResponse(
        Long freezeId,
        Long subscriptionId,
        int requestedDays,
        int usedDays,
        int returnedDays,
        LocalDateTime startAt,
        LocalDateTime actualEndAt,
        LocalDateTime expiryBefore,
        LocalDateTime newExpiryDate,
        boolean planEndReached,
        LocalDateTime expiresAt
) {}
