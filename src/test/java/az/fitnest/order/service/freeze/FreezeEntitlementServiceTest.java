package az.fitnest.order.service.freeze;

import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionFreezeEntitlement;
import az.fitnest.order.repository.SubscriptionFreezeEntitlementRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreezeEntitlementServiceTest {

    private static final Long SUBSCRIPTION_ID = 11L;
    private static final Long USER_ID = 22L;

    @Mock private SubscriptionFreezeEntitlementRepository entitlementRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionPackageRepository packageRepository;

    private FreezeEntitlementService service;

    @BeforeEach
    void setUp() {
        service = new FreezeEntitlementService(
                entitlementRepository,
                new FreezeTierPolicyProvider(),
                subscriptionRepository,
                packageRepository);
        when(entitlementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @ParameterizedTest
    @CsvSource({
            "Bronze, 5",
            "Silver, 7",
            "Gold, 10",
            "Platinum, 12"
    })
    void createForSubscriptionUsesNewTierLimit(String packageName, int expectedDays) {
        SubscriptionFreezeEntitlement created = service.createForSubscription(
                subscription(), packageName, "v1.2");

        assertEquals(expectedDays, created.getTotalDays());
        assertEquals(0, created.getConsumedDays());
        assertEquals(0, created.getReservedDays());
        assertEquals("v1.2", created.getPolicyVersion());
        assertEquals(YearMonth.now().toString(), created.getAllowanceMonth());
    }

    @Test
    void createForSubscriptionSkipsUnknownTier() {
        assertNull(service.createForSubscription(subscription(), "Trial", "v1.2"));
    }

    @Test
    void lazyProvisionFallsBackToBronzeLimit() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(subscription()));

        SubscriptionFreezeEntitlement provisioned = service.getBySubscriptionId(SUBSCRIPTION_ID).orElseThrow();

        assertEquals(5, provisioned.getTotalDays());
        assertEquals("v1.2", provisioned.getPolicyVersion());
        assertEquals(YearMonth.now().toString(), provisioned.getAllowanceMonth());
    }

    @Test
    void upgradeSubtractsUsedDaysFromNewLimit() {
        SubscriptionFreezeEntitlement old = entitlement(7, 3, 2, "2026-10");
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(old));

        service.onUpgrade(subscription(), "Gold");

        assertEquals(10, old.getTotalDays());
        assertEquals(3, old.getConsumedDays());
        assertEquals(2, old.getReservedDays());
        assertEquals(5, old.getAvailableDays());
        assertEquals("2026-10", old.getAllowanceMonth());
        assertEquals("v1.2", old.getPolicyVersion());
    }

    @Test
    void monthlyResetIsNonCumulativeAndIdempotent() {
        SubscriptionFreezeEntitlement entitlement = entitlement(5, 2, 3, "2026-10");
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));

        assertTrue(service.resetMonthlyAllowance(SUBSCRIPTION_ID, 5, "2026-11"));
        assertEquals(5, entitlement.getTotalDays());
        assertEquals(0, entitlement.getConsumedDays());
        assertEquals(3, entitlement.getReservedDays());
        assertEquals(2, entitlement.getAvailableDays());
        assertEquals("2026-11", entitlement.getAllowanceMonth());

        assertFalse(service.resetMonthlyAllowance(SUBSCRIPTION_ID, 5, "2026-11"));
        assertEquals(0, entitlement.getConsumedDays());
        verify(entitlementRepository, times(1)).save(entitlement);
    }

    @Test
    void finaliseConsumedChargesFreezeStartedInCurrentMonth() {
        SubscriptionFreezeEntitlement entitlement = entitlement(10, 1, 5, "2026-11");
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));

        service.finaliseConsumed(SUBSCRIPTION_ID, 3, 2, LocalDateTime.of(2026, 11, 15, 10, 0));

        assertEquals(4, entitlement.getConsumedDays());
        assertEquals(0, entitlement.getReservedDays());
        assertEquals(6, entitlement.getAvailableDays());
    }

    @Test
    void finaliseConsumedDoesNotCarryPreviousMonthUsageIntoNewMonth() {
        SubscriptionFreezeEntitlement entitlement = entitlement(10, 0, 5, "2026-12");
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));

        service.finaliseConsumed(SUBSCRIPTION_ID, 3, 2, LocalDateTime.of(2026, 11, 30, 10, 0));

        assertEquals(0, entitlement.getConsumedDays());
        assertEquals(0, entitlement.getReservedDays());
        assertEquals(10, entitlement.getAvailableDays());
    }

    @Test
    void reserveIncrementsReservedAndRejectsOverLimit() {
        SubscriptionFreezeEntitlement entitlement = entitlement(5, 0, 0, "2026-11");
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));

        service.reserve(SUBSCRIPTION_ID, 5);

        assertEquals(5, entitlement.getReservedDays());
        assertEquals(0, entitlement.getAvailableDays());
        assertThrows(IllegalStateException.class, () -> service.reserve(SUBSCRIPTION_ID, 1));
        assertEquals(5, entitlement.getReservedDays());
    }

    @Test
    void releaseReservedClampsAtZeroAndIgnoresMissingEntitlement() {
        SubscriptionFreezeEntitlement entitlement = entitlement(5, 0, 3, "2026-11");
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));

        service.releaseReserved(SUBSCRIPTION_ID, 10);
        assertEquals(0, entitlement.getReservedDays());

        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.empty());
        service.releaseReserved(SUBSCRIPTION_ID, 1); // no-op, must not throw
    }

    @Test
    void finaliseConsumedRejectsNegativeInputs() {
        SubscriptionFreezeEntitlement entitlement = entitlement(10, 0, 5, "2026-11");
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));

        assertThrows(IllegalArgumentException.class,
                () -> service.finaliseConsumed(SUBSCRIPTION_ID, -1, 0, LocalDateTime.now()));
        assertThrows(IllegalArgumentException.class,
                () -> service.finaliseConsumed(SUBSCRIPTION_ID, 1, -1, LocalDateTime.now()));

        assertEquals(0, entitlement.getConsumedDays());
        assertEquals(5, entitlement.getReservedDays());
    }

    @Test
    void resetMonthlyAllowanceRejectsInvalidInputAndMissingEntitlement() {
        assertFalse(service.resetMonthlyAllowance(SUBSCRIPTION_ID, 0, "2026-11"));
        assertFalse(service.resetMonthlyAllowance(SUBSCRIPTION_ID, -1, "2026-11"));
        assertFalse(service.resetMonthlyAllowance(SUBSCRIPTION_ID, 5, null));
        assertFalse(service.resetMonthlyAllowance(SUBSCRIPTION_ID, 5, " "));
        verify(entitlementRepository, never()).save(any());

        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.resetMonthlyAllowance(SUBSCRIPTION_ID, 5, "2026-11"));
    }

    @Test
    void resetMonthlyAllowanceKeepsBudgetInvariantWhenReservedExceedsNewLimit() {
        SubscriptionFreezeEntitlement entitlement = entitlement(30, 0, 12, "2026-10");
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.of(entitlement));

        assertTrue(service.resetMonthlyAllowance(SUBSCRIPTION_ID, 10, "2026-11"));

        assertEquals(12, entitlement.getTotalDays());
        assertEquals(0, entitlement.getConsumedDays());
        assertEquals(12, entitlement.getReservedDays());
        assertEquals(0, entitlement.getAvailableDays());
    }

    @Test
    void lazyProvisionSeedsConsumedDaysAndIsIdempotent() {
        Subscription sub = subscription();
        sub.setFrozenDaysUsed(2);
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));

        SubscriptionFreezeEntitlement first = service.getBySubscriptionId(SUBSCRIPTION_ID).orElseThrow();
        assertEquals(5, first.getTotalDays());
        assertEquals(2, first.getConsumedDays());

        when(entitlementRepository.findBySubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(first));
        SubscriptionFreezeEntitlement second = service.getBySubscriptionId(SUBSCRIPTION_ID).orElseThrow();
        assertSame(first, second);
        verify(entitlementRepository, times(1)).save(any());
    }

    @Test
    void getBySubscriptionIdReturnsEmptyWhenSubscriptionIsMissing() {
        when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

        assertTrue(service.getBySubscriptionId(SUBSCRIPTION_ID).isEmpty());
    }

    @Test
    void onUpgradeCreatesEntitlementWhenMissing() {
        when(entitlementRepository.findBySubscriptionIdForUpdate(SUBSCRIPTION_ID))
                .thenReturn(Optional.empty());

        service.onUpgrade(subscription(), "Platinum");

        ArgumentCaptor<SubscriptionFreezeEntitlement> captor =
                ArgumentCaptor.forClass(SubscriptionFreezeEntitlement.class);
        verify(entitlementRepository).save(captor.capture());
        SubscriptionFreezeEntitlement created = captor.getValue();
        assertEquals(12, created.getTotalDays());
        assertEquals("v1.2", created.getPolicyVersion());
        assertEquals(YearMonth.now().toString(), created.getAllowanceMonth());
    }

    private Subscription subscription() {
        Subscription sub = new Subscription();
        sub.setSubscriptionId(SUBSCRIPTION_ID);
        sub.setUserId(USER_ID);
        return sub;
    }

    private SubscriptionFreezeEntitlement entitlement(
            int totalDays, int consumedDays, int reservedDays, String allowanceMonth) {
        return SubscriptionFreezeEntitlement.builder()
                .id(1L)
                .subscriptionId(SUBSCRIPTION_ID)
                .userId(USER_ID)
                .totalDays(totalDays)
                .consumedDays(consumedDays)
                .reservedDays(reservedDays)
                .policyVersion("v1.2")
                .allowanceMonth(allowanceMonth)
                .build();
    }
}
