package az.fitnest.order.dto.freeze;

/** Optional guard so resume fails with preview_changed if the day bucket rolled. */
public record ResumeCommitRequest(
        Integer expectedUsedDays
) {}
