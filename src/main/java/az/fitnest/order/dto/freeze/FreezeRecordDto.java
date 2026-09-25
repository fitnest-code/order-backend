package az.fitnest.order.dto.freeze;

import az.fitnest.order.model.enums.FreezeStatus;

import java.time.LocalDateTime;

public record FreezeRecordDto(
        Long id,
        Long subscriptionId,
        Long userId,
        int requestedDays,
        Integer usedDays,
        Integer returnedDays,
        LocalDateTime startAt,
        LocalDateTime planEndAt,
        LocalDateTime actualEndAt,
        LocalDateTime expiryBefore,
        LocalDateTime expiryAfter,
        FreezeStatus status,
        String endedBy,
        LocalDateTime createdAt,
        String subscriptionName
) {}
