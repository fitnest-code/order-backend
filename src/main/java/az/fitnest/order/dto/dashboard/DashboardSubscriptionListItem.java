package az.fitnest.order.dto.dashboard;

public record DashboardSubscriptionListItem(
        Long subscriptionId,
        Long userId,
        Long packageId,
        String packageName,
        String status,
        String startAt,
        String endAt,
        Boolean isUpgraded
) {}
