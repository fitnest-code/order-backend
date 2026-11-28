package az.fitnest.order.service.dashboard;

import az.fitnest.order.dto.dashboard.DashboardSubscriptionSummaryResponse;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardSubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private SubscriptionPackageRepository subscriptionPackageRepository;
    @Mock
    private SubscriptionHomeGymRepository subscriptionHomeGymRepository;

    @InjectMocks
    private DashboardSubscriptionService service;

    @Test
    void summary_buildsCountsAndNullDelta() {
        when(subscriptionRepository.countByStatus("ACTIVE")).thenReturn(10L);
        when(subscriptionRepository.countByStatus("FROZEN")).thenReturn(2L);
        when(subscriptionRepository.countByStatusInAndEndAtBetween(anyList(), any(), any())).thenReturn(3L);
        when(subscriptionRepository.countByIsUpgradedAndStartAtBetween(eq(true), any(), any())).thenReturn(1L);
        when(subscriptionRepository.countByStartAtBetween(any(), any())).thenReturn(4L, 5L, 0L);
        when(subscriptionRepository.countGroupedByPackageAndStatus(anyList())).thenReturn(List.of());
        when(subscriptionRepository.countStartedByPackageBetween(any(), any())).thenReturn(List.of());
        when(subscriptionRepository.countEndingByPackageBetween(any(), any())).thenReturn(List.of());

        DashboardSubscriptionSummaryResponse res = service.getSummary(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        assertEquals(10L, res.activeCount());
        assertEquals(2L, res.frozenCount());
        assertEquals(3L, res.endedInPeriod());
        assertEquals(1L, res.renewedInPeriod());
        assertEquals(4L, res.last7DaysCount());
        assertNull(res.activeDeltaPct());
        assertTrue(res.activePositiveTrend());
    }
}
