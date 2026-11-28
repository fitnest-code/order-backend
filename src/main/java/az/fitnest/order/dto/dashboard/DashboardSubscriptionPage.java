package az.fitnest.order.dto.dashboard;

import java.util.List;

public record DashboardSubscriptionPage(
        List<DashboardSubscriptionListItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
