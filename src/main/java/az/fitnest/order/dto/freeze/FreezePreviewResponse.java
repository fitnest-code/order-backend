package az.fitnest.order.dto.freeze;

import java.time.LocalDateTime;

public record FreezePreviewResponse(
        String previewId,
        Long subscriptionId,
        int requestedDays,
        LocalDateTime currentExpiryDate,
        LocalDateTime newExpiryDate,
        LocalDateTime freezeStartAt,
        LocalDateTime freezePlanEndAt,
        LocalDateTime expiresAt
) {}
