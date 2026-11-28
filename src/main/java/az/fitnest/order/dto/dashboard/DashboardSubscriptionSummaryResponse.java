package az.fitnest.order.dto.dashboard;

import java.util.List;

public record DashboardSubscriptionSummaryResponse(
        long activeCount,
        long frozenCount,
        long endedInPeriod,
        long renewedInPeriod,
        long last7DaysCount,
        Double activeDeltaPct,
        boolean activePositiveTrend,
        List<Double> sparkline,
        List<DashboardPackageCard> packageCards,
        String dateFrom,
        String dateTo
) {}
