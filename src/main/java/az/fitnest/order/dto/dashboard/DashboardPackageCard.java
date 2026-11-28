package az.fitnest.order.dto.dashboard;

public record DashboardPackageCard(
        Long packageId,
        String packageName,
        long activeCount,
        long frozenCount,
        long last7DaysCount,
        long endingIn30DaysCount,
        double sharePct
) {}
