package az.fitnest.order.dto.freeze;

import az.fitnest.order.model.enums.FreezeStatus;

import java.time.LocalDateTime;

public record FreezeCommitResponse(
        Long freezeId,
        Long subscriptionId,
        int requestedDays,
        LocalDateTime startAt,
        LocalDateTime planEndAt,
        LocalDateTime expiryBefore,
        LocalDateTime expiryAfter,
        FreezeStatus status
) {}
