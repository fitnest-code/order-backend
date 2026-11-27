package az.fitnest.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.fitnest.order.client.GymPolicyV4Client;
import az.fitnest.order.dto.v6.PrimaryGymV6;
import az.fitnest.order.dto.v6.PrimaryGymV6.CheckIn;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.GymVisitPricingV4;
import az.fitnest.order.model.entity.PrimaryGymSelectionV4;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.repository.GymVisitPricingV4Repository;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.PrimaryGymSelectionV4Repository;
import az.fitnest.order.repository.SubscriptionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PrimaryGymV6ServiceTest {

  @Mock SubscriptionRepository subscriptions;
  @Mock GymVisitRepository visits;
  @Mock PrimaryGymSelectionV4Repository selections;
  @Mock GymVisitPricingV4Repository pricing;
  @Mock GymPolicyV4Client policies;
  @InjectMocks PrimaryGymV6Service service;

  Subscription sub;
  PrimaryGymSelectionV4 selection;
  LocalDateTime now;

  @BeforeEach
  void setUp() {
    now = LocalDateTime.now(PrimaryGymV4Service.ZONE);
    sub = new Subscription();
    sub.setSubscriptionId(10L);
    sub.setUserId(1L);
    sub.setPackageId(100L);
    sub.setStatus("ACTIVE");
    sub.setStartAt(now.minusDays(5));
    sub.setEndAt(now.plusDays(25));

    selection = new PrimaryGymSelectionV4();
    selection.setId(5L);
    selection.setUserId(1L);
    selection.setSubscriptionId(10L);
    selection.setGymId(50L);
    selection.setGymName("Gym");
    selection.setSelectedAt(now.minusDays(2));
    selection.setSource("FIRST_QR");
  }

  @Test
  void rejectsSubcategoryAfterTwelvePrimaryVisits() {
    stubCommon();
    when(visits
            .findBySubscriptionIdAndCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
                anyLong(), any(), any()))
        .thenReturn(twelvePrimaryVisits(50L));

    var ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.checkIn(
                    1L,
                    new CheckIn(
                        50L,
                        "req-sub-13",
                        7L,
                        PrimaryGymV6.ROLE_SUB,
                        new BigDecimal("5.00"),
                        null)));
    assertEquals("SUBCATEGORY_VISIT_LIMIT", ex.getReason());
    verify(visits, never()).saveAndFlush(any());
  }

  @Test
  void rejectsSubcategoryWithoutCategoryId() {
    var ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.checkIn(
                    1L,
                    new CheckIn(
                        50L,
                        "req-sub-no-cat",
                        null,
                        PrimaryGymV6.ROLE_SUB,
                        new BigDecimal("5.00"),
                        null)));
    assertEquals("CATEGORY_ID_REQUIRED", ex.getReason());
  }

  @Test
  void rejectsSubcategoryMonthlyPrice() {
    var ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.checkIn(
                    1L,
                    new CheckIn(
                        50L,
                        "req-sub-monthly",
                        7L,
                        PrimaryGymV6.ROLE_SUB,
                        new BigDecimal("5.00"),
                        new BigDecimal("80.00"))));
    assertEquals("SUBCATEGORY_MONTHLY_NOT_ALLOWED", ex.getReason());
  }

  @Test
  void allowsMainAfterTwelvePrimaryVisits() {
    stubCommon();
    when(visits
            .findBySubscriptionIdAndCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
                anyLong(), any(), any()))
        .thenReturn(twelvePrimaryVisits(50L));
    when(visits.saveAndFlush(any()))
        .thenAnswer(
            inv -> {
              GymVisit v = inv.getArgument(0);
              v.setId(99L);
              return v;
            });
    when(pricing.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var visit =
        service.checkIn(
            1L,
            new CheckIn(
                50L,
                "req-main-13",
                1L,
                PrimaryGymV6.ROLE_MAIN,
                new BigDecimal("4.00"),
                new BigDecimal("80.00")));

    assertEquals(99L, visit.id());
    assertEquals(PrimaryGymV6.ROLE_MAIN, visit.categoryRole());
    ArgumentCaptor<GymVisitPricingV4> cap = ArgumentCaptor.forClass(GymVisitPricingV4.class);
    verify(pricing).save(cap.capture());
    assertEquals(PrimaryGymV6.ROLE_MAIN, cap.getValue().getCategoryRole());
    assertEquals(13L, cap.getValue().getPrimaryVisits());
  }

  private void stubCommon() {
    when(subscriptions.findAllByUserIdOrderByStartAtDesc(1L)).thenReturn(List.of(sub));
    when(subscriptions.findByIdForUpdate(10L)).thenReturn(Optional.of(sub));
    when(pricing.findByUserIdAndRequestId(eq(1L), any())).thenReturn(Optional.empty());
    when(selections.findBySubscriptionIdOrderBySelectedAtDescIdDesc(10L))
        .thenReturn(List.of(selection));
    when(policies.quote(1L, 50L, 100L))
        .thenReturn(
            new GymPolicyV4Client.Quote(
                50L,
                "Gym",
                1L,
                true,
                true,
                null,
                new BigDecimal("4.00"),
                new BigDecimal("80.00"),
                "BRONZE",
                null,
                List.of(),
                List.of()));
  }

  private List<GymVisit> twelvePrimaryVisits(Long gymId) {
    return java.util.stream.IntStream.range(0, 12)
        .mapToObj(
            i ->
                GymVisit.builder()
                    .id((long) i + 1)
                    .userId(1L)
                    .gymId(gymId)
                    .subscriptionId(10L)
                    .checkedInAt(now.minusDays(12 - i).withHour(10))
                    .build())
        .toList();
  }
}
