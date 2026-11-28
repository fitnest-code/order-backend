package az.fitnest.order.dto.dashboard;

/** Earliest active/frozen startAt per user — used as purchaseDate on dashboard customers. */
public record DashboardSubscriptionStartItem(
        Long userId,
        String purchaseDate,
        String status,
        Long packageId
) {}
