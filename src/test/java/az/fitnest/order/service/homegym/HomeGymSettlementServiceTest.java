package az.fitnest.order.service.homegym;

import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.GymSettlementLine;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.enums.SettlementMode;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.repository.GymSettlementLineRepository;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refund path (spec "Kənar hallar"): the gym settlement report is recalculated from the
 * actual visits instead of keeping the line created by the monthly job.
 */
class HomeGymSettlementServiceTest {

    private final GymVisitRepository gymVisitRepository = mock(GymVisitRepository.class);
    private final GymSettlementLineRepository settlementLineRepository =
            mock(GymSettlementLineRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final SubscriptionEventPublisher eventPublisher = mock(SubscriptionEventPublisher.class);
    private final HomeGymSettlementService service = new HomeGymSettlementService(
            gymVisitRepository, settlementLineRepository, subscriptionRepository,
            new HomeGymPeriodCalculator(), eventPublisher);

    @Test
    void resettleReplacesPreviousLinesWithActualVisits() {
        Long subscriptionId = 42L;
        LocalDate periodStart = LocalDate.of(2026, 10, 5);
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(subscriptionId);
        subscription.setUserId(7L);
        subscription.setStartAt(LocalDateTime.of(2026, 10, 5, 9, 0));

        GymSettlementLine stale = GymSettlementLine.builder()
                .gymId(11L)
                .subscriptionId(subscriptionId)
                .periodStart(periodStart)
                .mode(SettlementMode.UNLIMITED)
                .amount(new BigDecimal("99.00"))
                .visits(20)
                .homeVisits(20)
                .build();
        GymVisit visit = GymVisit.builder()
                .userId(7L)
                .gymId(11L)
                .subscriptionId(subscriptionId)
                .checkedInAt(LocalDateTime.of(2026, 10, 6, 18, 0))
                .entryType(VisitEntryType.STANDARD)
                .periodStart(periodStart)
                .countsTowardsLimit(true)
                .singlePrice(new BigDecimal("7.50"))
                .build();

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(settlementLineRepository.findBySubscriptionId(subscriptionId)).thenReturn(List.of(stale));
        when(gymVisitRepository.findDistinctPeriodsBySubscriptionId(subscriptionId))
                .thenReturn(List.of(periodStart));
        when(gymVisitRepository.findBySubscriptionIdAndPeriodStartOrderByCheckedInAtAsc(
                subscriptionId, periodStart)).thenReturn(List.of(visit));
        when(settlementLineRepository.findByGymIdAndSubscriptionIdAndPeriodStart(
                anyLong(), anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());

        int created = service.resettleSubscription(subscriptionId);

        assertEquals(1, created);
        verify(settlementLineRepository).deleteAllInBatch(List.of(stale));
        ArgumentCaptor<GymSettlementLine> captor = ArgumentCaptor.forClass(GymSettlementLine.class);
        verify(settlementLineRepository).save(captor.capture());
        GymSettlementLine recalculated = captor.getValue();
        assertEquals(SettlementMode.PER_VISIT, recalculated.getMode());
        assertEquals(new BigDecimal("7.50"), recalculated.getAmount());
        assertEquals(1, recalculated.getVisits());
    }
}
