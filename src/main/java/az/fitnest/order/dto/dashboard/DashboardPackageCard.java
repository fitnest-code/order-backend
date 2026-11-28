package az.fitnest.order.dto.dashboard;

public record DashboardPackageCard(
        Long packageId,
        String packageName,
        long activeCount,
        long frozenCount
) {}
