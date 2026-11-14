package az.fitnest.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import az.fitnest.order.client.GymPolicyV4Client;
import az.fitnest.order.dto.v4.PrimaryGymV4.*;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class PrimaryGymV4ServiceTest {
  SubscriptionRepository subscriptions;
  GymVisitRepository visits;
  PrimaryGymSelectionV4Repository selections;
  GymVisitPricingV4Repository pricing;
  GymPolicyV4Client policies;
  PrimaryGymV4Service service;
  Subscription subscription;
  PrimaryGymSelectionV4 selected;
  LocalDateTime now;

  @BeforeEach
  void setup() {
    subscriptions = mock(SubscriptionRepository.class);
    visits = mock(GymVisitRepository.class);
    selections = mock(PrimaryGymSelectionV4Repository.class);
    pricing = mock(GymVisitPricingV4Repository.class);
    policies = mock(GymPolicyV4Client.class);
    service = new PrimaryGymV4Service(subscriptions, visits, selections, pricing, policies);
    now = LocalDateTime.now(PrimaryGymV4Service.ZONE);
    subscription = new Subscription();
    subscription.setSubscriptionId(1L);
    subscription.setUserId(2L);
    subscription.setPackageId(3L);
    subscription.setStatus("ACTIVE");
    subscription.setStartAt(now.minusMonths(3).withDayOfMonth(1).withHour(0));
    subscription.setEndAt(now.plusMonths(3));
    when(subscriptions.findAllByUserIdOrderByStartAtDesc(2L)).thenReturn(List.of(subscription));
    when(subscriptions.findByIdForUpdate(1L)).thenReturn(Optional.of(subscription));
    when(subscriptions.findById(1L)).thenReturn(Optional.of(subscription));
    when(visits
            .findBySubscriptionIdAndCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
                eq(1L), any(), any()))
        .thenReturn(List.of());
    selected = new PrimaryGymSelectionV4();
    selected.setId(10L);
    selected.setUserId(2L);
    selected.setSubscriptionId(1L);
    selected.setGymId(4L);
    selected.setGymName("Gym");
    selected.setSelectedAt(now.minusMonths(2));
    selected.setSource("ADMIN");
    when(selections.findBySubscriptionIdOrderBySelectedAtDescIdDesc(1L))
        .thenReturn(List.of(selected));
    when(policies.quote(eq(2L), anyLong(), eq(3L)))
        .thenAnswer(i -> quote(i.getArgument(1), true, List.of()));
    when(visits.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              GymVisit v = i.getArgument(0);
              v.setId(100L);
              return v;
            });
  }

  GymPolicyV4Client.Quote quote(Long gymId, boolean enabled, List<LocalDateTime> disabled) {
    return new GymPolicyV4Client.Quote(
        gymId,
        "Gym",
        1,
        true,
        enabled,
        disabled.isEmpty() ? null : disabled.getLast(),
        new BigDecimal("4.00"),
        new BigDecimal("50.00"),
        "BRONZE",
        null,
        disabled,
        List.of());
  }

  List<GymVisit> oldVisits(int count, Long gymId) {
    var list = new ArrayList<GymVisit>();
    for (int i = 0; i < count; i++)
      list.add(
          GymVisit.builder()
              .id((long) i + 1)
              .userId(2L)
              .subscriptionId(1L)
              .gymId(gymId)
              .checkedInAt(now.minusDays(1).minusMinutes(i))
              .build());
    return list;
  }

  void history(List<GymVisit> rows) {
    when(visits
            .findBySubscriptionIdAndCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
                eq(1L), any(), any()))
        .thenReturn(rows);
  }

  @Test
  void anniversaryDoesNotDriftAfterFebruary() {
    var anchor = LocalDateTime.of(2026, 1, 31, 0, 0);
    var feb =
        PrimaryGymV4Service.cycle(
            anchor, anchor.plusYears(1), LocalDateTime.of(2026, 3, 30, 12, 0));
    assertEquals(LocalDateTime.of(2026, 2, 28, 0, 0), feb.start());
    assertEquals(LocalDateTime.of(2026, 3, 31, 0, 0), feb.end());
    var march =
        PrimaryGymV4Service.cycle(anchor, anchor.plusYears(1), LocalDateTime.of(2026, 3, 31, 0, 0));
    assertEquals(LocalDateTime.of(2026, 3, 31, 0, 0), march.start());
  }

  @Test
  void cycleClampsToSubscriptionEnd() {
    var anchor = LocalDateTime.of(2026, 10, 5, 0, 0);
    var end = LocalDateTime.of(2026, 10, 25, 0, 0);
    assertEquals(end, PrimaryGymV4Service.cycle(anchor, end, anchor.plusDays(2)).end());
  }

  @Test
  void thirteenthPrimaryVisitBypassesGeneralLimit() {
    history(oldVisits(12, 4L));
    var result = service.checkIn(2L, new CheckIn(4L, "request-13"));
    assertTrue(result.primary());
    assertEquals(13, result.primaryVisits());
    assertEquals(13, result.generalVisits());
    verify(subscriptions).findByIdForUpdate(1L);
    verify(pricing).save(any());
  }

  @Test
  void thirtyFirstPrimaryVisitIsRejected() {
    history(oldVisits(30, 4L));
    assertEquals(
        "PRIMARY_VISIT_LIMIT",
        assertThrows(
                ResponseStatusException.class,
                () -> service.checkIn(2L, new CheckIn(4L, "request-31")))
            .getReason());
    verify(visits, never()).saveAndFlush(any());
  }

  @Test
  void generalLimitDoesNotClearPrimaryOnDeniedEntry() {
    history(oldVisits(12, 4L));
    assertEquals(
        "GENERAL_VISIT_LIMIT",
        assertThrows(
                ResponseStatusException.class, () -> service.checkIn(2L, new CheckIn(5L, "other")))
            .getReason());
    assertNull(selected.getEndedAt());
    verify(visits, never()).saveAndFlush(any());
  }

  @Test
  void confirmedOtherGymEntryClearsPrimaryAndCountsVisit() {
    history(oldVisits(7, 4L));
    var result = service.checkIn(2L, new CheckIn(5L, "other"));
    assertFalse(result.primary());
    assertEquals(8, result.generalVisits());
    assertEquals("OTHER_GYM_CHECKIN", selected.getEndReason());
  }

  @Test
  void primaryGymHasOneVisitPerBakuDay() {
    history(List.of(GymVisit.builder().gymId(4L).checkedInAt(now).build()));
    assertEquals(
        "ALREADY_ENTERED_TODAY",
        assertThrows(
                ResponseStatusException.class, () -> service.checkIn(2L, new CheckIn(4L, "today")))
            .getReason());
  }

  @Test
  void retryReturnsOriginalVisitWithoutCheckingPolicyAgain() {
    var row = new GymVisitPricingV4();
    row.setVisitId(100L);
    row.setGymId(4L);
    row.setCheckedInAt(now);
    when(pricing.findByUserIdAndRequestId(2L, "retry")).thenReturn(Optional.of(row));
    assertEquals(100L, service.checkIn(2L, new CheckIn(4L, "retry")).id());
    verifyNoInteractions(policies);
    verify(visits, never()).saveAndFlush(any());
  }

  @Test
  void reusedKeyCannotChangeTargetGym() {
    var row = new GymVisitPricingV4();
    row.setGymId(5L);
    when(pricing.findByUserIdAndRequestId(2L, "retry")).thenReturn(Optional.of(row));
    assertEquals(
        "IDEMPOTENCY_KEY_REUSED",
        assertThrows(
                ResponseStatusException.class, () -> service.checkIn(2L, new CheckIn(4L, "retry")))
            .getReason());
  }

  @Test
  void primaryCannotChangeAfterUsage() {
    history(oldVisits(1, 4L));
    assertEquals(
        "PRIMARY_ALREADY_USED_THIS_MONTH",
        assertThrows(ResponseStatusException.class, () -> service.select(2L, 5L, "ADMIN"))
            .getReason());
  }

  @Test
  void disablingPreservesCurrentCycle() {
    when(policies.quote(2L, 4L, 3L)).thenReturn(quote(4L, false, List.of(now.minusHours(1))));
    assertEquals(4L, service.status(2L).gymId());
  }

  @Test
  void reenableDoesNotReviveAnExpiredSelection() {
    when(policies.quote(2L, 4L, 3L)).thenReturn(quote(4L, true, List.of(now.minusMonths(1))));
    assertNull(service.status(2L).gymId());
  }

  @Test
  void earliestDisableWinsAcrossRepeatedToggles() {
    when(policies.quote(2L, 4L, 3L))
        .thenReturn(quote(4L, false, List.of(now.minusMonths(1), now.minusHours(1))));
    assertNull(service.status(2L).gymId());
  }

  @Test
  void frozenSubscriptionCannotEnter() {
    subscription.setStatus("FROZEN");
    assertThrows(
        ResponseStatusException.class, () -> service.checkIn(2L, new CheckIn(4L, "frozen")));
    verify(visits, never()).saveAndFlush(any());
  }

  @Test
  void settlementAtTwelveUsesPerEntryAndThirteenUsesFlatRate() {
    var cycleStart = now.minusMonths(1).withDayOfMonth(1);
    var rows = new ArrayList<GymVisitPricingV4>();
    for (int i = 0; i < 13; i++) {
      var r = new GymVisitPricingV4();
      r.setVisitId((long) i);
      r.setGymId(4L);
      r.setCycleStart(cycleStart);
      r.setCycleEnd(now.minusDays(1));
      r.setPrimaryVisit(true);
      r.setSelectionId(10L);
      r.setEntryPrice(new BigDecimal("4.00"));
      r.setMonthlyPrice(new BigDecimal("50.00"));
      rows.add(r);
    }
    when(selections.findById(10L)).thenReturn(Optional.of(selected));
    when(pricing.findBySubscriptionIdAndGymIdAndCycleStartOrderByCheckedInAtAscVisitIdAsc(
            1L, 4L, cycleStart))
        .thenReturn(rows.subList(0, 12));
    history(oldVisits(12, 4L));
    assertEquals(new BigDecimal("48.00"), service.settlement(1L, 4L, cycleStart).amount());
    when(pricing.findBySubscriptionIdAndGymIdAndCycleStartOrderByCheckedInAtAscVisitIdAsc(
            1L, 4L, cycleStart))
        .thenReturn(rows);
    history(oldVisits(13, 4L));
    assertEquals(new BigDecimal("50.00"), service.settlement(1L, 4L, cycleStart).amount());
    assertTrue(service.settlement(1L, 4L, cycleStart).flatMonthly());
    selected.setEndedAt(cycleStart.plusDays(20));
    selected.setEndReason("OTHER_GYM_CHECKIN");
    assertEquals(new BigDecimal("52.00"), service.settlement(1L, 4L, cycleStart).amount());
  }

  @Test
  void anotherGymOnTheSameDayCountsAsGeneralAndClearsPrimary() {
    history(List.of(GymVisit.builder().gymId(4L).checkedInAt(now.minusMinutes(30)).build()));
    var result = service.checkIn(2L, new CheckIn(5L, "other-today"));
    assertFalse(result.primary());
    assertEquals(2, result.generalVisits());
    assertEquals("OTHER_GYM_CHECKIN", selected.getEndReason());
  }

  @Test
  void firstQrSelectsAnEligiblePrimaryGym() {
    when(selections.findBySubscriptionIdOrderBySelectedAtDescIdDesc(1L)).thenReturn(List.of());
    when(selections.save(any()))
        .thenAnswer(
            i -> {
              PrimaryGymSelectionV4 p = i.getArgument(0);
              p.setId(20L);
              return p;
            });
    var result = service.checkIn(2L, new CheckIn(4L, "first-qr"));
    assertTrue(result.primary());
    assertEquals(1, result.primaryVisits());
    verify(selections)
        .save(argThat(p -> p.getSource().equals("FIRST_QR") && p.getGymId().equals(4L)));
  }

  @Test
  void disabledPrimaryOptionStillAllowsGeneralEntry() {
    when(selections.findBySubscriptionIdOrderBySelectedAtDescIdDesc(1L)).thenReturn(List.of());
    when(policies.quote(2L, 4L, 3L)).thenReturn(quote(4L, false, List.of()));
    var result = service.checkIn(2L, new CheckIn(4L, "general"));
    assertFalse(result.primary());
    assertEquals(1, result.generalVisits());
    verify(selections, never()).save(any());
  }
}
