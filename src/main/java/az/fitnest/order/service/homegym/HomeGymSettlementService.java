package az.fitnest.order.service.homegym;

import az.fitnest.order.event.SubscriptionEvent;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.model.entity.GymSettlementLine;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.enums.SettlementMode;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.repository.GymSettlementLineRepository;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Monthly gym settlement (spec R15-R17).
 *
 * <p>Every subscription-month is settled as a whole for every gym the subscriber visited.
 * The amount is per-visit (sum of the {@code single_price} snapshots) unless the gym was the
 * subscriber's home gym and it has at least 13 home-gym visits in that subscription-month.
 * In that case exactly one unlimited price is charged, taken from the package of the
 * majority time slot and from the price version effective at the 13th home visit.
 *
 * <p>The job runs on the 1st of every month at 03:00 and settles subscription-months whose
 * last day fell in the previous calendar month (R17). Settlement lines are unique per
 * {@code (gym_id, subscription_id, period_start)}, so reruns are safe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HomeGymSettlementService {

    public static final int UNLIMITED_VISIT_THRESHOLD = 13;

    private final GymVisitRepository gymVisitRepository;
    private final GymSettlementLineRepository settlementLineRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final HomeGymPeriodCalculator periodCalculator;
    private final SubscriptionEventPublisher eventPublisher;

    /**
     * Settles all subscription-months that ended in {@code runMonth.minusMonths(1)}.
     *
     * @return number of created settlement lines
     */
    @Transactional
    public int settleMonth(YearMonth runMonth) {
        YearMonth settledMonth = runMonth.minusMonths(1);
        LocalDate windowStart = settledMonth.atDay(1);
        LocalDate windowEnd = runMonth.atDay(1);
        // A subscription-month ending in the settled month starts between the first day of
        // the previous month and the first day of the settled month (short months clamp).
        LocalDate periodStartFrom = windowStart.minusMonths(1);
        LocalDate periodStartTo = windowEnd;

        List<Object[]> periods = gymVisitRepository.findDistinctSubscriptionPeriods(
                periodStartFrom, periodStartTo);
        int created = 0;
        for (Object[] row : periods) {
            Long subscriptionId = asLong(row[0]);
            LocalDate periodStart = asDate(row[1]);
            if (subscriptionId == null || periodStart == null) {
                continue;
            }
            Subscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
            if (subscription == null) {
                log.warn("Skipping settlement for missing subscription {}", subscriptionId);
                continue;
            }
            LocalDate periodEndExclusive = periodCalculator
                    .periodEndsAt(subscription.getStartAt(), periodStart).toLocalDate();
            // The period's last day must belong to the settled calendar month. A period that
            // ends exactly at windowStart ended on the last day of the previous month and was
            // already covered by the previous run; windowEnd itself is exclusive, so a period
            // ending there has its last day in the settled month.
            if (!periodEndExclusive.isAfter(windowStart) || periodEndExclusive.isAfter(windowEnd)) {
                continue;
            }
            created += settlePeriod(runMonth, subscription, periodStart);
        }
        log.info("Home-gym settlement for runMonth={} settled {} period(s), created {} line(s)",
                runMonth, periods.size(), created);
        return created;
    }

    private int settlePeriod(YearMonth runMonth, Subscription subscription, LocalDate periodStart) {
        Long subscriptionId = subscription.getSubscriptionId();
        List<GymVisit> visits = gymVisitRepository
                .findBySubscriptionIdAndPeriodStartOrderByCheckedInAtAsc(subscriptionId, periodStart);
        if (visits.isEmpty()) {
            return 0;
        }

        Map<Long, List<GymVisit>> byGym = new LinkedHashMap<>();
        for (GymVisit visit : visits) {
            if (visit.getGymId() == null) {
                continue;
            }
            byGym.computeIfAbsent(visit.getGymId(), key -> new ArrayList<>()).add(visit);
        }

        int created = 0;
        for (Map.Entry<Long, List<GymVisit>> entry : byGym.entrySet()) {
            Long gymId = entry.getKey();
            if (settlementLineRepository
                    .findByGymIdAndSubscriptionIdAndPeriodStart(gymId, subscriptionId, periodStart)
                    .isPresent()) {
                continue;
            }
            SettlementResult result = calculate(entry.getValue());
            GymSettlementLine line = GymSettlementLine.builder()
                    .runMonth(runMonth.toString())
                    .gymId(gymId)
                    .subscriptionId(subscriptionId)
                    .periodStart(periodStart)
                    .periodEnd(periodCalculator.periodEndsAt(subscription.getStartAt(), periodStart)
                            .toLocalDate().minusDays(1))
                    .visits(entry.getValue().size())
                    .homeVisits(result.homeVisits())
                    .mode(result.mode())
                    .pricePackage(result.pricePackage())
                    .amount(result.amount())
                    .breakdown(result.breakdown())
                    .createdAt(periodCalculator.now())
                    .build();
            settlementLineRepository.save(line);
            publishCompleted(subscription, line);
            created++;
        }
        return created;
    }

    private SettlementResult calculate(List<GymVisit> gymVisits) {
        List<GymVisit> homeVisits = gymVisits.stream()
                .filter(v -> v.getEntryType() == VisitEntryType.HOME_UNLIMITED)
                .filter(v -> v.getHomeGymId() != null)
                .toList();
        List<GymVisit> standardVisits = gymVisits.stream()
                .filter(v -> v.getEntryType() != VisitEntryType.HOME_UNLIMITED)
                .toList();

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("visits", gymVisits.size());
        breakdown.put("homeVisits", homeVisits.size());
        breakdown.put("standardVisits", standardVisits.size());

        if (homeVisits.size() >= UNLIMITED_VISIT_THRESHOLD) {
            UnlimitedPrice choice = chooseUnlimitedPrice(homeVisits);
            BigDecimal unlimited = money(choice.price() == null ? BigDecimal.ZERO : choice.price());
            // STANDARD entries are always charged per visit, even when the home gym hits
            // the unlimited threshold (spec R16/§"Zala ödəniş").
            BigDecimal standardTotal = money(sumSinglePrices(standardVisits));
            BigDecimal amount = money(unlimited.add(standardTotal));
            breakdown.put("rule", "HOME_GYM_UNLIMITED");
            breakdown.put("pricePackage", choice.pricePackage());
            breakdown.put("unlimitedPrice", unlimited);
            breakdown.put("standardVisitsAmount", standardTotal);
            breakdown.put("threshold", UNLIMITED_VISIT_THRESHOLD);
            return new SettlementResult(SettlementMode.UNLIMITED, choice.pricePackage(),
                    homeVisits.size(), amount, breakdown);
        }

        BigDecimal total = sumSinglePrices(gymVisits);
        int pricedVisits = (int) gymVisits.stream()
                .filter(visit -> visit.getSinglePrice() != null)
                .count();
        BigDecimal amount = money(total);
        breakdown.put("rule", "PER_VISIT");
        breakdown.put("pricedVisits", pricedVisits);
        breakdown.put("singlePriceTotal", amount);
        return new SettlementResult(SettlementMode.PER_VISIT, null, homeVisits.size(), amount, breakdown);
    }

    private BigDecimal sumSinglePrices(List<GymVisit> visits) {
        BigDecimal total = BigDecimal.ZERO;
        for (GymVisit visit : visits) {
            if (visit.getSinglePrice() != null) {
                total = total.add(visit.getSinglePrice());
            }
        }
        return total;
    }

    /**
     * Majority package by visit count; ties are resolved by the higher unlimited price (R16).
     * The price version is the one effective at the 13th home visit: for the winning package
     * we take its first visit at/after the 13th visit, otherwise its last visit.
     */
    private UnlimitedPrice chooseUnlimitedPrice(List<GymVisit> homeVisits) {
        LocalDateTime thirteenthAt = homeVisits.get(UNLIMITED_VISIT_THRESHOLD - 1).getCheckedInAt();

        Map<String, List<GymVisit>> byPackage = new LinkedHashMap<>();
        for (GymVisit visit : homeVisits) {
            String pkg = visit.getPricePackage() != null ? visit.getPricePackage() : "UNKNOWN";
            byPackage.computeIfAbsent(pkg, key -> new ArrayList<>()).add(visit);
        }

        String bestPackage = null;
        BigDecimal bestPrice = null;
        int bestCount = -1;
        for (Map.Entry<String, List<GymVisit>> entry : byPackage.entrySet()) {
            List<GymVisit> packageVisits = entry.getValue();
            BigDecimal price = versionPrice(packageVisits, thirteenthAt);
            int count = packageVisits.size();
            if (count > bestCount
                    || (count == bestCount && higher(price, bestPrice))) {
                bestPackage = entry.getKey();
                bestPrice = price;
                bestCount = count;
            }
        }
        return new UnlimitedPrice(bestPackage, bestPrice);
    }

    private BigDecimal versionPrice(List<GymVisit> packageVisits, LocalDateTime thirteenthAt) {
        GymVisit reference = packageVisits.stream()
                .filter(v -> v.getUnlimitedPrice() != null)
                .filter(v -> !v.getCheckedInAt().isBefore(thirteenthAt))
                .min(Comparator.comparing(GymVisit::getCheckedInAt))
                .orElseGet(() -> packageVisits.stream()
                        .filter(v -> v.getUnlimitedPrice() != null)
                        .max(Comparator.comparing(GymVisit::getCheckedInAt))
                        .orElse(null));
        return reference != null ? reference.getUnlimitedPrice() : null;
    }

    private boolean higher(BigDecimal candidate, BigDecimal current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        return candidate.compareTo(current) > 0;
    }

    private void publishCompleted(Subscription subscription, GymSettlementLine line) {
        try {
            SubscriptionEvent event = new SubscriptionEvent(subscription.getUserId(),
                    "SETTLEMENT_COMPLETED", subscription.getSubscriptionId());
            event.setGymId(line.getGymId());
            event.setAmount(line.getAmount().toPlainString());
            event.setMode(line.getMode().name());
            event.setRunMonth(line.getRunMonth());
            eventPublisher.publishSubscriptionEvent(subscription.getUserId(), event.getAction(),
                    subscription.getSubscriptionId(), event);
        } catch (Exception e) {
            log.warn("Could not publish SETTLEMENT_COMPLETED for subscription {} gym {}: {}",
                    line.getSubscriptionId(), line.getGymId(), e.getMessage());
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private LocalDate asDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return value != null ? LocalDate.parse(Objects.toString(value)) : null;
    }

    private record UnlimitedPrice(String pricePackage, BigDecimal price) {
    }

    private record SettlementResult(SettlementMode mode, String pricePackage, int homeVisits,
                                    BigDecimal amount, Map<String, Object> breakdown) {
    }
}
