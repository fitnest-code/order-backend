package az.fitnest.order.service;

import az.fitnest.order.client.GymPolicyV4Client;
import az.fitnest.order.dto.v4.PrimaryGymV4.*;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PrimaryGymV4Service {
  private final SubscriptionRepository subscriptions;
  private final GymVisitRepository visits;
  private final PrimaryGymSelectionV4Repository selections;
  private final GymVisitPricingV4Repository pricing;
  private final GymPolicyV4Client policies;
  public static final ZoneId ZONE = ZoneId.of("Asia/Baku");
  public static final int GENERAL_LIMIT = 12, PRIMARY_LIMIT = 30;

  public static Cycle cycle(
      LocalDateTime subscriptionStart, LocalDateTime subscriptionEnd, LocalDateTime now) {
    long months =
        ChronoUnit.MONTHS.between(
            subscriptionStart.toLocalDate().withDayOfMonth(1), now.toLocalDate().withDayOfMonth(1));
    LocalDateTime start = subscriptionStart.plusMonths(months);
    if (start.isAfter(now)) start = subscriptionStart.plusMonths(--months);
    LocalDateTime end = subscriptionStart.plusMonths(months + 1);
    if (subscriptionEnd != null && subscriptionEnd.isBefore(end)) end = subscriptionEnd;
    return new Cycle(start, end);
  }

  private Subscription subscription(Long userId, boolean lock) {
    if (userId == null) throw error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    var candidates = subscriptions.findAllByUserIdOrderByStartAtDesc(userId);
    var s =
        candidates.stream()
            .filter(x -> List.of("ACTIVE", "FINISHED").contains(x.getStatus()))
            .findFirst()
            .orElseThrow(() -> error(HttpStatus.CONFLICT, "NO_ACTIVE_SUBSCRIPTION"));
    if (lock) s = subscriptions.findByIdForUpdate(s.getSubscriptionId()).orElseThrow();
    var now = LocalDateTime.now(ZONE);
    if (!List.of("ACTIVE", "FINISHED").contains(s.getStatus())
        || s.getStartAt().isAfter(now)
        || s.getEndAt() == null
        || !s.getEndAt().isAfter(now)) throw error(HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_ACTIVE");
    return s;
  }

  private List<GymVisit> cycleVisits(Subscription s, Cycle c) {
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

  private LocalDateTime disabledExpiry(PrimaryGymSelectionV4 selection, GymPolicyV4Client.Quote q) {
    var history =
        q.primaryDisableHistory() == null ? List.<LocalDateTime>of() : q.primaryDisableHistory();
    return history.stream()
        .filter(at -> at.isAfter(selection.getSelectedAt()))
        .map(at -> cycleFor(selection.getSubscriptionId(), at).end())
        .min(Comparator.naturalOrder())
        .orElse(null);
  }

  private boolean honored(
      PrimaryGymSelectionV4 selection, GymPolicyV4Client.Quote q, Cycle cycle, LocalDateTime now) {
    var expiry = disabledExpiry(selection, q);
    if (expiry != null) return now.isBefore(expiry);
    return q.primaryEnabled();
  }

  private Cycle cycleFor(Long id, LocalDateTime time) {
    var s = subscriptions.findById(id).orElseThrow();
    return cycle(s.getStartAt(), s.getEndAt(), time);
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

  @Transactional
  public Status select(Long userId, Long gymId, String source) {
    var s = subscription(userId, true);
    var now = LocalDateTime.now(ZONE);
    var c = cycle(s.getStartAt(), s.getEndAt(), now);
    var all = cycleVisits(s, c);
    var p = current(s);
    if (p != null) {
      var existing = policies.quote(userId, p.getGymId(), s.getPackageId());
      if (!honored(p, existing, c, now)) {
        close(p, now, "PRIMARY_DISABLED");
        p = null;
      }
    }
    if (p != null && p.getGymId().equals(gymId)) return status(userId);
    var history = selections.findBySubscriptionIdOrderBySelectedAtDescIdDesc(s.getSubscriptionId());
    if (history.stream()
        .anyMatch(
            h ->
                all.stream()
                    .anyMatch(
                        v ->
                            v.getGymId().equals(h.getGymId())
                                && !v.getCheckedInAt().isBefore(h.getSelectedAt())
                                && (h.getEndedAt() == null
                                    || !v.getCheckedInAt().isAfter(h.getEndedAt())))))
      throw error(HttpStatus.CONFLICT, "PRIMARY_ALREADY_USED_THIS_MONTH");
    var q = policies.quote(userId, gymId, s.getPackageId());
    if (!q.primaryEnabled() || q.primaryMonthlyPrice() == null)
      throw error(HttpStatus.CONFLICT, "PRIMARY_SELECTION_DISABLED");
    if (p != null) close(p, now, "CHANGED");
    newSelection(s, gymId, q.gymName(), source, now);
    return status(userId);
  }

  @Transactional
  public Status status(Long userId) {
    var s = subscription(userId, true);
    var now = LocalDateTime.now(ZONE);
    var c = cycle(s.getStartAt(), s.getEndAt(), now);
    var all = cycleVisits(s, c);
    var p = current(s);
    LocalDateTime expires = s.getEndAt();
    List<AccessWindow> hours = List.of();
    if (p != null) {
      var q = policies.quote(userId, p.getGymId(), s.getPackageId());
      var disabledEnd = disabledExpiry(p, q);
      if (disabledEnd != null) expires = disabledEnd;
      hours = q.accessWindows() == null ? List.of() : q.accessWindows();
      if (!honored(p, q, c, now)) {
        close(p, expires, "PRIMARY_DISABLED");
        p = null;
        hours = List.of();
      }
    }
    final var selected = p;
    long used =
        p == null ? 0 : all.stream().filter(v -> v.getGymId().equals(selected.getGymId())).count();
    var history = selections.findBySubscriptionIdOrderBySelectedAtDescIdDesc(s.getSubscriptionId());
    boolean canChange =
        history.stream()
            .noneMatch(
                h ->
                    all.stream()
                        .anyMatch(
                            v ->
                                v.getGymId().equals(h.getGymId())
                                    && !v.getCheckedInAt().isBefore(h.getSelectedAt())
                                    && (h.getEndedAt() == null
                                        || !v.getCheckedInAt().isAfter(h.getEndedAt()))));
    return new Status(
        s.getSubscriptionId(),
        s.getPackageId(),
        c,
        p == null ? null : p.getGymId(),
        p == null ? null : p.getGymName(),
        p == null ? null : p.getSelectedAt(),
        p == null ? null : p.getSource(),
        p == null ? null : expires,
        used,
        PRIMARY_LIMIT,
        all.size(),
        GENERAL_LIMIT,
        canChange,
        history.stream()
            .map(
                h ->
                    new History(
                        h.getId(),
                        h.getGymId(),
                        h.getGymName(),
                        h.getSelectedAt(),
                        h.getEndedAt(),
                        h.getSource(),
                        h.getEndReason()))
            .toList(),
        hours);
  }

  @Transactional
  public Visit checkIn(Long userId, CheckIn input) {
    var s = subscription(userId, true);
    var replay = pricing.findByUserIdAndRequestId(userId, input.requestId());
    if (replay.isPresent()) {
      if (!replay.get().getGymId().equals(input.gymId()))
        throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
      return response(replay.get());
    }
    var now = LocalDateTime.now(ZONE);
    var c = cycle(s.getStartAt(), s.getEndAt(), now);
    var all = cycleVisits(s, c);
    var q = policies.quote(userId, input.gymId(), s.getPackageId());
    if (!q.allowed()) throw error(HttpStatus.FORBIDDEN, q.reason());
    var p = current(s);
    if (p != null) {
      var pq =
          p.getGymId().equals(input.gymId())
              ? q
              : policies.quote(userId, p.getGymId(), s.getPackageId());
      if (!honored(p, pq, c, now)) {
        close(p, now, "PRIMARY_DISABLED");
        p = null;
      }
    }
    // First QR can select a primary gym only if no prior selection was used in this cycle.
    if (p == null && q.primaryEnabled()) {
      var history =
          selections.findBySubscriptionIdOrderBySelectedAtDescIdDesc(s.getSubscriptionId());
      boolean used =
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
      if (!used) p = newSelection(s, input.gymId(), q.gymName(), "FIRST_QR", now);
    }
    boolean primary = p != null && p.getGymId().equals(input.gymId());
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
    if (primary && primaryCount >= PRIMARY_LIMIT)
      throw error(HttpStatus.CONFLICT, "PRIMARY_VISIT_LIMIT");
    if (!primary && all.size() >= GENERAL_LIMIT)
      throw error(HttpStatus.CONFLICT, "GENERAL_VISIT_LIMIT");
    if (!primary && p != null) close(p, now, "OTHER_GYM_CHECKIN");
    if (q.entryPrice() == null || primary && q.primaryMonthlyPrice() == null)
      throw error(HttpStatus.CONFLICT, "PRICE_NOT_CONFIGURED");
    var v =
        visits.saveAndFlush(
            GymVisit.builder()
                .userId(userId)
                .gymId(input.gymId())
                .subscriptionId(s.getSubscriptionId())
                .checkedInAt(now)
                .build());
    var r = new GymVisitPricingV4();
    r.setVisitId(v.getId());
    r.setUserId(userId);
    r.setRequestId(input.requestId());
    r.setSubscriptionId(s.getSubscriptionId());
    r.setGymId(input.gymId());
    r.setSelectionId(primary ? p.getId() : null);
    r.setCheckedInAt(now);
    r.setCycleStart(c.start());
    r.setCycleEnd(c.end());
    r.setPrimaryVisit(primary);
    r.setGeneralVisits(all.size() + 1);
    r.setPrimaryVisits(primary ? primaryCount + 1 : 0);
    r.setEntryPrice(q.entryPrice());
    r.setMonthlyPrice(q.primaryMonthlyPrice());
    r.setPriceTier(q.priceTier());
    r.setPolicyVersion(q.policyVersion());
    pricing.save(r);
    return response(r);
  }

  private Visit response(GymVisitPricingV4 r) {
    return new Visit(
        r.getVisitId(),
        r.getGymId(),
        r.getCheckedInAt(),
        r.isPrimaryVisit(),
        r.getGeneralVisits(),
        r.getPrimaryVisits(),
        r.getEntryPrice(),
        r.getMonthlyPrice(),
        r.getPriceTier());
  }

  @Transactional(readOnly = true)
  public List<Affected> affected(Long gymId) {
    var now = LocalDateTime.now(ZONE);
    var quotes = new HashMap<Long, GymPolicyV4Client.Quote>();
    var result = new ArrayList<Affected>();
    for (var p : selections.findByGymIdAndEndedAtIsNull(gymId)) {
      var s = subscriptions.findById(p.getSubscriptionId()).orElseThrow();
      if (!List.of("ACTIVE", "FINISHED", "FROZEN").contains(s.getStatus())
          || s.getEndAt() == null
          || !s.getEndAt().isAfter(now)) continue;
      var c = cycle(s.getStartAt(), s.getEndAt(), now);
      var q =
          quotes.computeIfAbsent(s.getPackageId(), id -> policies.quote(p.getUserId(), gymId, id));
      if (!honored(p, q, c, now)) continue;
      var disabledEnd = disabledExpiry(p, q);
      result.add(
          new Affected(
              p.getUserId(),
              s.getSubscriptionId(),
              s.getPackageId(),
              gymId,
              p.getSelectedAt(),
              disabledEnd == null ? c.end() : disabledEnd,
              cycleVisits(s, c).stream().filter(v -> v.getGymId().equals(gymId)).count()));
    }
    return result;
  }

  @Transactional(readOnly = true)
  public Settlement settlement(Long subscriptionId, Long gymId, LocalDateTime cycleStart) {
    var rows =
        pricing.findBySubscriptionIdAndGymIdAndCycleStartOrderByCheckedInAtAscVisitIdAsc(
            subscriptionId, gymId, cycleStart);
    if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "NO_PRICED_VISITS");
    var first = rows.getFirst();
    var allVisits =
        visits
            .findBySubscriptionIdAndCheckedInAtGreaterThanEqualAndCheckedInAtLessThanOrderByCheckedInAtAsc(
                subscriptionId, first.getCycleStart(), first.getCycleEnd());
    if (allVisits.stream().filter(v -> v.getGymId().equals(gymId)).count() != rows.size())
      throw error(HttpStatus.CONFLICT, "LEGACY_VISITS_REQUIRE_RECONCILIATION");
    if (LocalDateTime.now(ZONE).isBefore(first.getCycleEnd()))
      throw error(HttpStatus.CONFLICT, "CYCLE_NOT_FINISHED");
    var primaryRows = rows.stream().filter(GymVisitPricingV4::isPrimaryVisit).toList();
    boolean flat = false;
    BigDecimal amount =
        rows.stream()
            .map(GymVisitPricingV4::getEntryPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (primaryRows.size() > 12) {
      var thirteenth = primaryRows.get(12);
      var p = selections.findById(thirteenth.getSelectionId()).orElseThrow();
      flat = p.getEndedAt() == null || !p.getEndedAt().isBefore(first.getCycleEnd());
      if (flat)
        amount =
            thirteenth
                .getMonthlyPrice()
                .add(
                    rows.stream()
                        .filter(r -> !r.isPrimaryVisit())
                        .map(GymVisitPricingV4::getEntryPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
    return new Settlement(
        subscriptionId,
        gymId,
        new Cycle(first.getCycleStart(), first.getCycleEnd()),
        rows.size(),
        flat,
        amount,
        "AZN");
  }

  private static ResponseStatusException error(HttpStatus status, String reason) {
    return new ResponseStatusException(status, reason);
  }
}
