package az.fitnest.order.service.homegym;

import az.fitnest.order.repository.GymVisitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * R19/R20 selection gate tests.
 *
 * <p>Without an active home gym the selection stays open while the subscription month
 * has either no visits (any gym) or visits at exactly one gym (only that gym). Two or
 * more gyms close the selection until the next subscription month.
 */
@ExtendWith(MockitoExtension.class)
class HomeGymSelectionPolicyTest {

    private static final long SUBSCRIPTION_ID = 42L;
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 5);

    @Mock
    private GymVisitRepository gymVisitRepository;

    @InjectMocks
    private HomeGymSelectionPolicy policy;

    @Test
    void noVisitsKeepsSelectionOpenForAnyGym() {
        when(gymVisitRepository.countCountableVisitsByGym(SUBSCRIPTION_ID, PERIOD_START))
                .thenReturn(List.of());

        HomeGymSelectionPolicy.SelectionState state = policy.evaluate(SUBSCRIPTION_ID, PERIOD_START);

        assertTrue(state.open());
        assertNull(state.soleGymId());
        assertEquals(0, state.soleGymVisits());
        assertTrue(state.noVisits());
        assertFalse(state.multipleGyms());
    }

    @Test
    void singleVisitedGymBecomesTheOnlySelectableGym() { // R19: 10 visits at FitZone
        when(gymVisitRepository.countCountableVisitsByGym(SUBSCRIPTION_ID, PERIOD_START))
                .thenReturn(List.<Object[]>of(new Object[]{5L, 10L}));

        HomeGymSelectionPolicy.SelectionState state = policy.evaluate(SUBSCRIPTION_ID, PERIOD_START);

        assertTrue(state.open());
        assertNull(state.blockedReason());
        assertEquals(5L, state.soleGymId());
        assertEquals(10, state.soleGymVisits());
        assertEquals(10, state.totalVisits());
        assertFalse(state.noVisits());
        assertFalse(state.multipleGyms());
    }

    @Test
    void visitsAtTwoGymsCloseTheSelectionForThePeriod() { // R20 / T-31
        when(gymVisitRepository.countCountableVisitsByGym(SUBSCRIPTION_ID, PERIOD_START))
                .thenReturn(List.of(new Object[]{5L, 10L}, new Object[]{7L, 2L}));

        HomeGymSelectionPolicy.SelectionState state = policy.evaluate(SUBSCRIPTION_ID, PERIOD_START);

        assertFalse(state.open());
        assertEquals(HomeGymSelectionPolicy.MULTIPLE_GYMS_THIS_PERIOD, state.blockedReason());
        assertNull(state.soleGymId());
        assertTrue(state.multipleGyms());
        assertEquals(12, state.totalVisits());
    }

    @Test
    void malformedRowsAreIgnoredInsteadOfBlockingSelection() {
        when(gymVisitRepository.countCountableVisitsByGym(SUBSCRIPTION_ID, PERIOD_START))
                .thenReturn(List.of(new Object[]{null, 4L}, new Object[]{5L, 3L}, new Object[]{7L}));

        HomeGymSelectionPolicy.SelectionState state = policy.evaluate(SUBSCRIPTION_ID, PERIOD_START);

        assertTrue(state.open());
        assertEquals(5L, state.soleGymId());
        assertEquals(3, state.soleGymVisits());
        assertFalse(state.multipleGyms());
    }

    @Test
    void repeatedRowsForTheSameGymAreMerged() {
        when(gymVisitRepository.countCountableVisitsByGym(SUBSCRIPTION_ID, PERIOD_START))
                .thenReturn(List.of(new Object[]{5L, 2L}, new Object[]{5L, 3L}));

        HomeGymSelectionPolicy.SelectionState state = policy.evaluate(SUBSCRIPTION_ID, PERIOD_START);

        assertTrue(state.open());
        assertEquals(5L, state.soleGymId());
        assertEquals(5, state.soleGymVisits());
        assertEquals(1, state.visitsByGym().size());
    }
}
