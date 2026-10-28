package az.fitnest.order.service.homegym;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.HomeGymEndReason;
import az.fitnest.order.model.enums.HomeGymSource;
import az.fitnest.order.model.enums.HomeGymStatus;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.HomeGymIdempotencyRepository;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Refund and F-04 package-removal handling of the home gym (spec "Kənar hallar"). */
@ExtendWith(MockitoExtension.class)
class HomeGymRefundAndPackageRemovalTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionHomeGymRepository homeGymRepository;
    @Mock private GymVisitRepository gymVisitRepository;
    @Mock private HomeGymIdempotencyRepository idempotencyRepository;
    @Mock private CatalogServiceGrpcClient catalogClient;
    @Mock private HomeGymPeriodCalculator periodCalculator;
    @Mock private HomeGymSelectionPolicy selectionPolicy;
    @Mock private SubscriptionEventPublisher eventPublisher;
    @Mock private HomeGymSettlementService settlementService;
    @Mock private ObjectMapper objectMapper;
    @Mock private SubscriptionPackageRepository packageRepository;

    @InjectMocks private HomeGymService service;

    @Test
    void refundEndsTheHomeGymAndRecalculatesSettlement() {
        Subscription subscription = subscription(5L, 9L, 100L);
        SubscriptionHomeGym active = SubscriptionHomeGym.builder()
                .id(1L)
                .subscriptionId(5L)
                .userId(9L)
                .gymId(100L)
                .status(HomeGymStatus.ACTIVE)
                .source(HomeGymSource.SUBSCRIPTION_SCREEN)
                .selectedAt(LocalDateTime.of(2026, 10, 6, 10, 0))
                .build();

        when(periodCalculator.now()).thenReturn(LocalDateTime.of(2026, 10, 27, 12, 0));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(active));
        when(catalogClient.getHomeGymInfo(anyLong(), any())).thenReturn(
                new CatalogServiceGrpcClient.HomeGymInfo(true, "FitZone", true, true, null));

        service.onRefund(5L);

        assertEquals(HomeGymStatus.ENDED, active.getStatus());
        assertEquals(HomeGymEndReason.REFUND, active.getEndReason());
        assertNull(active.getEndsAt());
        assertEquals(EntryMode.STANDARD, subscription.getEntryMode());
        assertEquals(LocalDateTime.of(2026, 10, 27, 12, 0), subscription.getRefundedAt());
        verify(eventPublisher).publishSubscriptionEvent(eq(9L), eq("HOME_GYM_ENDED"), eq(5L), any());
        verify(settlementService).resettleSubscription(5L);
    }

    @Test
    void repeatedRefundKeepsTheFirstRefundTimestamp() {
        Subscription subscription = subscription(5L, 9L, 100L);
        LocalDateTime firstRefund = LocalDateTime.of(2026, 10, 20, 9, 0);
        subscription.setRefundedAt(firstRefund);

        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.empty());

        service.onRefund(5L);

        assertEquals(firstRefund, subscription.getRefundedAt());
        verify(settlementService).resettleSubscription(5L);
    }

    @Test
    void packageRemovalSchedulesR18EndForSubscribersWithoutTheGym() {
        Subscription subscription = subscription(5L, 9L, 3L);
        SubscriptionHomeGym active = SubscriptionHomeGym.builder()
                .id(1L)
                .subscriptionId(5L)
                .userId(9L)
                .gymId(100L)
                .status(HomeGymStatus.ACTIVE)
                .source(HomeGymSource.SUBSCRIPTION_SCREEN)
                .selectedAt(LocalDateTime.of(2026, 10, 6, 10, 0))
                .build();

        when(periodCalculator.periodEndsAt(subscription.getStartAt()))
                .thenReturn(LocalDateTime.of(2026, 11, 5, 0, 0));
        when(periodCalculator.toOffset(LocalDateTime.of(2026, 11, 5, 0, 0)))
                .thenReturn(java.time.OffsetDateTime.of(2026, 11, 5, 0, 0, 0, 0,
                        java.time.ZoneOffset.ofHours(4)));
        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of(5L));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(homeGymRepository.findActiveForUpdate(5L)).thenReturn(Optional.of(active));
        when(packageRepository.findById(3L)).thenReturn(Optional.of(packageEntity(3L, "Gold")));
        when(catalogClient.getHomeGymInfo(100L, 3L)).thenReturn(
                new CatalogServiceGrpcClient.HomeGymInfo(true, "FitZone", false, true, null));

        int affected = service.onGymPackageRemoved(100L, List.of(3L), "FitZone");

        assertEquals(1, affected);
        assertEquals(LocalDateTime.of(2026, 11, 5, 0, 0), active.getEndsAt());
        assertEquals(HomeGymEndReason.GYM_HOME_DISABLED, active.getEndReason());
        assertEquals(HomeGymStatus.ACTIVE, active.getStatus());
        verify(eventPublisher).publishSubscriptionEvent(eq(9L), eq("HOME_GYM_PACKAGE_REMOVED"), eq(5L), any());
    }

    @Test
    void packageRemovalKeepsSelectionWhenTheGymIsStillInThePackage() {
        Subscription subscription = subscription(5L, 9L, 3L);
        when(homeGymRepository.findActiveSubscriptionIdsByGymId(100L)).thenReturn(List.of(5L));
        when(subscriptionRepository.findById(5L)).thenReturn(Optional.of(subscription));
        when(catalogClient.getHomeGymInfo(100L, 3L)).thenReturn(
                new CatalogServiceGrpcClient.HomeGymInfo(true, "FitZone", true, true, null));

        int affected = service.onGymPackageRemoved(100L, List.of(3L), "FitZone");

        assertEquals(0, affected);
        verify(homeGymRepository, org.mockito.Mockito.never()).findActiveForUpdate(anyLong());
    }

    private az.fitnest.order.model.entity.SubscriptionPackage packageEntity(Long id, String name) {
        var pkg = new az.fitnest.order.model.entity.SubscriptionPackage();
        pkg.setId(id);
        pkg.setName(name);
        return pkg;
    }

    private Subscription subscription(Long id, Long userId, Long packageId) {        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(id);
        subscription.setUserId(userId);
        subscription.setPackageId(packageId);
        subscription.setStartAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        subscription.setEntryMode(EntryMode.HOME_GYM);
        subscription.setStatus("ACTIVE");
        return subscription;
    }
}
