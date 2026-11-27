package az.fitnest.order.service;

import az.fitnest.order.client.GymPolicyV4Client;
import az.fitnest.order.dto.v4.PrimaryGymV4;
import az.fitnest.order.dto.v6.PrimaryGymV6;
import az.fitnest.order.dto.v6.PrimaryGymV6.CheckIn;
import az.fitnest.order.dto.v6.PrimaryGymV6.Settlement;
import az.fitnest.order.dto.v6.PrimaryGymV6.Visit;
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
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Category-aware primary check-in (v6).
 *
 * <p>At the selected primary gym, visits 1–12 may be MAIN or SUB; visits 13–30 are MAIN only.
 * SUB always bills the catalog single-entry price. Membership monthly applies only to MAIN after
 * the 12th primary visit. v4 {@link PrimaryGymV4Service} is left unchanged.
 */
@Service
@RequiredArgsConstructor
public class PrimaryGymV6Service {

  public static final int GENERAL_LIMIT = PrimaryGymV4Service.GENERAL_LIMIT;
  public static final int PRIMARY_LIMIT = PrimaryGymV4Service.PRIMARY_LIMIT;
  /** Shared pool before MAIN-only slot opens (13–30). */
  public static final int SHARED_CATEGORY_LIMIT = 12;

  private final SubscriptionRepository subscriptions;
  private final GymVisitRepository visits;
  private final PrimaryGymSelectionV4Repository selections;
  private final GymVisitPricingV4Repository pricing;
  private final GymPolicyV4Client policies;

  @Transactional
  public Visit checkIn(Long userId, CheckIn input) {
    var role = normalizeRole(input.categoryRole());
    if (PrimaryGymV6.ROLE_SUB.equals(role) && input.categoryId() == null) {
      throw error(HttpStatus.BAD_REQUEST, "CATEGORY_ID_REQUIRED");
    }
    if (PrimaryGymV6.ROLE_SUB.equals(role) && input.monthlyPrice() != null) {
      throw error(HttpStatus.BAD_REQUEST, "SUBCATEGORY_MONTHLY_NOT_ALLOWED");
    }

    var s = lockSubscription(userId);
    var replay = pricing.findByUserIdAndRequestId(userId, input.requestId());
    if (replay.isPresent()) {
      if (!replay.get().getGymId().equals(input.gymId())) {
        throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
      }
      return toVisit(
          replay.get(),
          visits.findById(replay.get().getVisitId()).map(GymVisit::getCategoryId).orElse(null));
    }

    var now = LocalDateTime.now(PrimaryGymV4Service.ZONE);
    var cycle = PrimaryGymV4Service.cycle(s.getStartAt(), s.getEndAt(), now);
    var all = cycleVisits(s, cycle);
    var quote = policies.quote(userId, input.gymId(), s.getPackageId());
    if (!quote.allowed()) {
      throw error(HttpStatus.FORBIDDEN, quote.reason());
    }

    var selection = current(s);
    if (selection != null) {
      var selectionQuote =
          selection.getGymId().equals(input.gymId())
              ? quote
              : policies.quote(userId, selection.getGymId(), s.getPackageId());
      if (!honored(selection, selectionQuote, s, now)) {
        close(selection, now, "PRIMARY_DISABLED");
        selection = null;
      }
    }

    if (selection == null && quote.primaryEnabled()) {
      var history =
          selections.findBySubscriptionIdOrderBySelectedAtDescIdDesc(s.getSubscriptionId());
      boolean usedInCycle =
          history.stream()
              .anyMatch(
                  h ->
                      all.stream()
                          .anyMatch(
                              v ->
                                  v.getGymId().equals(h.getGymId())
                                      && !v.getCheckedInAt().isBefore(h.getSelectedAt())
                                      && (h.getEndedAt() == null
                                          || !v.getCheckedInAt().isAfter(h.getEndedAt()))));
      if (!usedInCycle) {
        selection = newSelection(s, input.gymId(), quote.gymName(), "FIRST_QR", now);
      }
    }

    boolean primary = selection != null && selection.getGymId().equals(input.gymId());
    if (primary
        && all.stream()
            .anyMatch(
                v ->
                    v.getGymId().equals(input.gymId())
                        && v.getCheckedInAt().toLocalDate().equals(now.toLocalDate()))) {
      throw error(HttpStatus.CONFLICT, "ALREADY_ENTERED_TODAY");
    }
    if (!primary
        && all.stream()
            .anyMatch(
                v ->
                    v.getGymId().equals(input.gymId())
                        && v.getCheckedInAt().isAfter(now.minusHours(2)))) {
      throw error(HttpStatus.CONFLICT, "ALREADY_SCANNED_RECENTLY");
    }

    long primaryCount =
        primary ? all.stream().filter(v -> v.getGymId().equals(input.gymId())).count() : 0;

    if (primary && PrimaryGymV6.ROLE_SUB.equals(role) && primaryCount >= SHARED_CATEGORY_LIMIT) {
      throw error(HttpStatus.CONFLICT, "SUBCATEGORY_VISIT_LIMIT");
    }
    if (primary && primaryCount >= PRIMARY_LIMIT) {
      throw error(HttpStatus.CONFLICT, "PRIMARY_VISIT_LIMIT");
    }
    if (!primary && all.size() >= GENERAL_LIMIT) {
      throw error(HttpStatus.CONFLICT, "GENERAL_VISIT_LIMIT");
    }
    if (!primary && selection != null) {
      close(selection, now, "OTHER_GYM_CHECKIN");
    }

    if (input.entryPrice() == null) {
      throw error(HttpStatus.CONFLICT, "PRICE_NOT_CONFIGURED");
    }
    if (primary
        && PrimaryGymV6.ROLE_MAIN.equals(role)
        && (input.monthlyPrice() == null && quote.primaryMonthlyPrice() == null)) {
      throw error(HttpStatus.CONFLICT, "PRICE_NOT_CONFIGURED");
    }

    var visit =
        visits.saveAndFlush(
            GymVisit.builder()
                .userId(userId)
                .gymId(input.gymId())
                .subscriptionId(s.getSubscriptionId())
                .checkedInAt(now)
                .categoryId(input.categoryId())
                .build());

    BigDecimal monthly =
        PrimaryGymV6.ROLE_SUB.equals(role)
            ? null
            : (input.monthlyPrice() != null ? input.monthlyPrice() : quote.primaryMonthlyPrice());

    var row = new GymVisitPricingV4();
    row.setVisitId(visit.getId());
    row.setUserId(userId);
    row.setRequestId(input.requestId());
    row.setSubscriptionId(s.getSubscriptionId());
    row.setGymId(input.gymId());
    row.setSelectionId(primary ? selection.getId() : null);
    row.setCheckedInAt(now);
    row.setCycleStart(cycle.start());
    row.setCycleEnd(cycle.end());
    row.setPrimaryVisit(primary);
    row.setGeneralVisits(all.size() + 1);
    row.setPrimaryVisits(primary ? primaryCount + 1 : 0);
    row.setEntryPrice(input.entryPrice());
    row.setMonthlyPrice(monthly);
    row.setPriceTier(quote.priceTier() == null ? "ENTRY" : quote.priceTier());
    row.setPolicyVersion(quote.policyVersion());
    row.setCategoryRole(role);
    pricing.save(row);
    return toVisit(row, visit.getCategoryId());
  }

  @Transactional(readOnly = true)
  public Settlement settlement(Long subscriptionId, Long gymId, LocalDateTime cycleStart) {
    var rows =
        pricing.findBySubscriptionIdAndGymIdAndCycleStartOrderByCheckedInAtAscVisitIdAsc(
            subscriptionId, gymId, cycleStart);
    if (rows.isEmpty()) {
      throw error(HttpStatus.NOT_FOUND, "NO_PRICED_VISITS");
    }
    var first = rows.getFirst();
    var allVisits =
        visits
            .findBySubscriptionIdAndCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
                subscriptionId, first.getCycleStart(), first.getCycleEnd());
    if (allVisits.stream().filter(v -> v.getGymId().equals(gymId)).count() != rows.size()) {
      throw error(HttpStatus.CONFLICT, "LEGACY_VISITS_REQUIRE_RECONCILIATION");
    }
    if (LocalDateTime.now(PrimaryGymV4Service.ZONE).isBefore(first.getCycleEnd())) {
      throw error(HttpStatus.CONFLICT, "CYCLE_NOT_FINISHED");
    }

    var primaryRows = rows.stream().filter(GymVisitPricingV4::isPrimaryVisit).toList();
    var subSum =
        rows.stream()
            .filter(r -> PrimaryGymV6.ROLE_SUB.equals(r.getCategoryRole()))
            .map(GymVisitPricingV4::getEntryPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    var nonPrimarySum =
        rows.stream()
            .filter(r -> !r.isPrimaryVisit())
            .map(GymVisitPricingV4::getEntryPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    boolean flat = false;
    BigDecimal amount;
    if (primaryRows.size() > SHARED_CATEGORY_LIMIT) {
      var thirteenth = primaryRows.get(SHARED_CATEGORY_LIMIT);
      var selection = selections.findById(thirteenth.getSelectionId()).orElseThrow();
      flat =
          selection.getEndedAt() == null
              || !selection.getEndedAt().isBefore(first.getCycleEnd());
      if (flat) {
        // Monthly covers MAIN primary visits; SUB lines stay per-entry.
        amount = thirteenth.getMonthlyPrice().add(subSum).add(nonPrimarySum);
      } else {
        amount =
            rows.stream()
                .map(GymVisitPricingV4::getEntryPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
      }
    } else {
      amount =
          rows.stream()
              .map(GymVisitPricingV4::getEntryPrice)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    return new Settlement(
        subscriptionId,
        gymId,
        new PrimaryGymV6.Cycle(first.getCycleStart(), first.getCycleEnd()),
        rows.size(),
        flat,
        amount,
        "AZN");
  }

  private static String normalizeRole(String raw) {
    if (raw == null || raw.isBlank()) {
      return PrimaryGymV6.ROLE_MAIN;
    }
    return raw.trim().toUpperCase();
  }

  private Subscription lockSubscription(Long userId) {
    if (userId == null) {
      throw error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }
    var candidates = subscriptions.findAllByUserIdOrderByStartAtDesc(userId);
    var s =
        candidates.stream()
            .filter(x -> List.of("ACTIVE", "FINISHED").contains(x.getStatus()))
            .findFirst()
            .orElseThrow(() -> error(HttpStatus.CONFLICT, "NO_ACTIVE_SUBSCRIPTION"));
    s = subscriptions.findByIdForUpdate(s.getSubscriptionId()).orElseThrow();
    var now = LocalDateTime.now(PrimaryGymV4Service.ZONE);
    if (!List.of("ACTIVE", "FINISHED").contains(s.getStatus())
        || s.getStartAt().isAfter(now)
        || s.getEndAt() == null
        || !s.getEndAt().isAfter(now)) {
      throw error(HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_ACTIVE");
    }
    return s;
  }

  private List<GymVisit> cycleVisits(Subscription s, PrimaryGymV4.Cycle c) {
    return visits
        .findBySubscriptionIdAndCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
            s.getSubscriptionId(), c.start(), c.end());
  }

  private PrimaryGymSelectionV4 current(Subscription s) {
    return selections
        .findBySubscriptionIdOrderBySelectedAtDescIdDesc(s.getSubscriptionId())
        .stream()
        .filter(x -> x.getEndedAt() == null)
        .findFirst()
        .orElse(null);
  }

  private LocalDateTime disabledExpiry(
      PrimaryGymSelectionV4 selection, GymPolicyV4Client.Quote q, Subscription s) {
    var history =
        q.primaryDisableHistory() == null ? List.<LocalDateTime>of() : q.primaryDisableHistory();
    return history.stream()
        .filter(at -> at.isAfter(selection.getSelectedAt()))
        .map(at -> PrimaryGymV4Service.cycle(s.getStartAt(), s.getEndAt(), at).end())
        .min(Comparator.naturalOrder())
        .orElse(null);
  }

  private boolean honored(
      PrimaryGymSelectionV4 selection,
      GymPolicyV4Client.Quote q,
      Subscription s,
      LocalDateTime now) {
    var expiry = disabledExpiry(selection, q, s);
    if (expiry != null) {
      return now.isBefore(expiry);
    }
    return q.primaryEnabled();
  }

  private void close(PrimaryGymSelectionV4 p, LocalDateTime at, String reason) {
    p.setEndedAt(at);
    p.setEndReason(reason);
    selections.saveAndFlush(p);
  }

  private PrimaryGymSelectionV4 newSelection(
      Subscription s, Long gymId, String gymName, String source, LocalDateTime now) {
    var p = new PrimaryGymSelectionV4();
    p.setUserId(s.getUserId());
    p.setSubscriptionId(s.getSubscriptionId());
    p.setGymId(gymId);
    p.setGymName(gymName);
    p.setSource(source);
    p.setSelectedAt(now);
    return selections.save(p);
  }

  private Visit toVisit(GymVisitPricingV4 r, Long categoryId) {
    return new Visit(
        r.getVisitId(),
        r.getGymId(),
        r.getCheckedInAt(),
        r.isPrimaryVisit(),
        r.getGeneralVisits(),
        r.getPrimaryVisits(),
        r.getEntryPrice(),
        r.getMonthlyPrice(),
        r.getPriceTier(),
        categoryId,
        r.getCategoryRole());
  }

  private static ResponseStatusException error(HttpStatus status, String reason) {
    return new ResponseStatusException(status, reason);
  }
}
