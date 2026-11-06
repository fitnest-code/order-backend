package az.fitnest.order.service.visithistory;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.config.HomeGymLimits;
import az.fitnest.order.dto.visithistory.VisitHistoryQuery;
import az.fitnest.order.dto.visithistory.VisitHistoryResponse;
import az.fitnest.order.exception.VisitHistoryException;
import az.fitnest.order.model.entity.GymVisit;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionHomeGym;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.model.entity.VisitAttempt;
import az.fitnest.order.model.enums.EntryMode;
import az.fitnest.order.model.enums.HomeGymStatus;
import az.fitnest.order.model.enums.VisitEntryType;
import az.fitnest.order.model.enums.VisitHistoryMode;
import az.fitnest.order.model.enums.VisitHistoryPreset;
import az.fitnest.order.model.enums.VisitHistoryStatus;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.SubscriptionHomeGymRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import az.fitnest.order.repository.VisitAttemptRepository;
import az.fitnest.order.service.homegym.HomeGymPeriodCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read model of {@code GET /api/v1/me/visits/history} (spec "A. Tarixçə API").
 *
 * <p>Successful visits live in {@code gym_visits}, rejected attempts in
 * {@code visit_attempt}. A page is built by fetching at most {@code limit + 1} rows from
 * each table, merging them on scan time and applying an opaque cursor. All date
 * boundaries are evaluated in Asia/Baku.
 *
 * <p>Aggregates ({@code rangeTotals}, {@code gymsInRange}) intentionally ignore the
 * {@code status}/{@code gymId} row filters so the chips and the gym dropdown keep their
 * counts while a filter is active.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisitHistoryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final String VISIT = "VISIT";
    private static final String ATTEMPT = "ATTEMPT";

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPackageRepository packageRepository;
    private final SubscriptionHomeGymRepository homeGymRepository;
    private final GymVisitRepository gymVisitRepository;
    private final VisitAttemptRepository attemptRepository;
    private final CurrentSubscriptionResolver subscriptionResolver;
    private final HomeGymPeriodCalculator periodCalculator;
    private final HomeGymLimits homeGymLimits;
    private final CatalogServiceGrpcClient catalogClient;
    private final MessageSource messageSource;

    private static final Comparator<Entry> NEWEST_FIRST = Comparator
            .comparing(Entry::scannedAt).reversed()
            // On equal scan times VISIT sorts before ATTEMPT, matching the cursor rules.
            .thenComparingInt(entry -> VISIT.equals(entry.kind()) ? 0 : 1)
            .thenComparing(Entry::id, Comparator.reverseOrder());

    @Transactional(readOnly = true)
    public VisitHistoryResponse getHistory(Long userId, VisitHistoryQuery request) {
        if (userId == null) {
            throw new VisitHistoryException("UNAUTHORIZED", "error.unauthorized");
        }
        LocalDate today = periodCalculator.now().toLocalDate();
        Subscription current = subscriptionResolver.resolve(userId);
        Range range = resolveRange(userId, request, today, current);
        int limit = normaliseLimit(request.limit());

        String status = request.status() != null ? request.status().name() : VisitHistoryStatus.ALL.name();
        String mode = request.mode() != null ? request.mode().name() : VisitHistoryMode.ALL.name();
        LocalDateTime from = range.from().atStartOfDay();
        LocalDateTime to = range.to().plusDays(1).atStartOfDay();
        Cursor cursor = parseCursor(request.cursor());

        boolean includeVisits = !VisitHistoryStatus.REJECTED.name().equals(status);
        boolean includeAttempts = !VisitHistoryStatus.SUCCESS.name().equals(status);
        // Flags, not JDBC nulls. A null gym id or cursor makes Postgres fail the query
        // with "could not determine data type of parameter" and the endpoint returns 500.
        boolean filterByGym = request.gymId() != null;
        Long gymId = filterByGym ? request.gymId() : 0L;
        boolean hasCursor = cursor.scannedAt() != null;
        LocalDateTime cursorTs = hasCursor ? cursor.scannedAt() : LocalDateTime.of(1970, 1, 1, 0, 0);
        String cursorKind = hasCursor ? cursor.kind() : VISIT;
        Long cursorId = hasCursor ? cursor.id() : 0L;
        PageRequest pageRequest = PageRequest.of(0, limit + 1);

        List<GymVisit> visits = includeVisits
                ? gymVisitRepository.findHistory(userId, from, to, mode, filterByGym, gymId,
                hasCursor, cursorTs, cursorKind, cursorId, pageRequest)
                : List.of();
        List<VisitAttempt> attempts = includeAttempts
                ? attemptRepository.findHistory(userId, from, to, mode, filterByGym, gymId,
                hasCursor, cursorTs, cursorKind, cursorId, pageRequest)
                : List.of();

        List<Entry> merged = new ArrayList<>(visits.size() + attempts.size());
        for (GymVisit visit : visits) {
            merged.add(new Entry(VISIT, visit.getId(), visit.getCheckedInAt(), visit, null));
        }
        for (VisitAttempt attempt : attempts) {
            merged.add(new Entry(ATTEMPT, attempt.getId(), attempt.getScannedAt(), null, attempt));
        }
        merged.sort(NEWEST_FIRST);

        boolean hasMore = merged.size() > limit;
        List<Entry> page = hasMore ? merged.subList(0, limit) : merged;
        String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1)) : null;

        Set<Long> subscriptionIds = new HashSet<>();
        Set<Long> gymIds = new HashSet<>();
        Map<Long, String> gymNames = new HashMap<>();
        List<GymVisit> pageVisits = new ArrayList<>();
        for (Entry entry : page) {
            if (entry.visit() != null) {
                subscriptionIds.add(entry.visit().getSubscriptionId());
                gymIds.add(entry.visit().getGymId());
                pageVisits.add(entry.visit());
                if (entry.visit().getGymName() != null) {
                    gymNames.putIfAbsent(entry.visit().getGymId(), entry.visit().getGymName());
                }
            } else {
                subscriptionIds.add(entry.attempt().getSubscriptionId());
                gymIds.add(entry.attempt().getGymId());
                if (entry.attempt().getGymName() != null) {
                    gymNames.putIfAbsent(entry.attempt().getGymId(), entry.attempt().getGymName());
                }
            }
        }

        Map<Long, Subscription> subscriptions = loadSubscriptions(subscriptionIds);
        Map<Long, String> packages = loadPackageNames(subscriptions);
        Map<Long, VisitHistoryResponse.CounterAfter> counters = buildCounters(pageVisits, subscriptions);

        long success = gymVisitRepository.countHistory(userId, from, to, mode);
        long rejected = attemptRepository.countHistory(userId, from, to, mode);
        Map<Long, Long> gymCounts = new LinkedHashMap<>();
        mergeGymCounts(gymCounts, gymVisitRepository.countHistoryByGym(userId, from, to, mode));
        mergeGymCounts(gymCounts, attemptRepository.countHistoryByGym(userId, from, to, mode));
        gymIds.addAll(gymCounts.keySet());
        Long packageId = current != null ? current.getPackageId() : null;
        resolveMissingGymNames(userId, gymIds, gymNames, packageId);

        List<VisitHistoryResponse.Item> items = new ArrayList<>(page.size());
        for (Entry entry : page) {
            if (entry.visit() != null) {
                GymVisit visit = entry.visit();
                items.add(new VisitHistoryResponse.Item(
                        "v_" + visit.getId(),
                        VISIT,
                        visit.getGymId(),
                        gymNames.get(visit.getGymId()),
                        packages.get(visit.getSubscriptionId()),
                        periodCalculator.toOffset(visit.getCheckedInAt()),
                        visit.getEntryType() == VisitEntryType.HOME_UNLIMITED
                                ? VisitHistoryMode.HOME_GYM.name() : VisitHistoryMode.STANDARD.name(),
                        VisitHistoryStatus.SUCCESS.name(),
                        counters.get(visit.getId()),
                        null));
            } else {
                VisitAttempt attempt = entry.attempt();
                String rejectCode = attempt.getRejectCode();
                items.add(new VisitHistoryResponse.Item(
                        "a_" + attempt.getId(),
                        ATTEMPT,
                        attempt.getGymId(),
                        gymNames.get(attempt.getGymId()),
                        attempt.getPackageCode() != null
                                ? attempt.getPackageCode() : packages.get(attempt.getSubscriptionId()),
                        periodCalculator.toOffset(attempt.getScannedAt()),
                        attempt.getMode(),
                        VisitHistoryStatus.REJECTED.name(),
                        null,
                        new VisitHistoryResponse.RejectReason(rejectCode, rejectText(rejectCode))));
            }
        }

        List<VisitHistoryResponse.GymInRange> gymsInRange = gymCounts.entrySet().stream()
                .sorted(Comparator
                        .comparingLong((Map.Entry<Long, Long> entry) -> entry.getValue()).reversed()
                        .thenComparing(entry -> gymNames.getOrDefault(entry.getKey(), "")))
                .map(entry -> new VisitHistoryResponse.GymInRange(
                        entry.getKey(), gymNames.get(entry.getKey()), entry.getValue()))
                .toList();

        return new VisitHistoryResponse(
                new VisitHistoryResponse.Range(range.preset().name(), range.from(), range.to(), range.days()),
                buildSubscriptionPeriod(current, today),
                new VisitHistoryResponse.RangeTotals(success, rejected, gymCounts.size()),
                gymsInRange,
                items,
                nextCursor);
    }

    // ------------------------------------------------------------------
    // Range
    // ------------------------------------------------------------------

    private Range resolveRange(Long userId, VisitHistoryQuery request, LocalDate today, Subscription current) {
        VisitHistoryPreset preset = request.preset() != null
                ? request.preset() : VisitHistoryPreset.LAST_30_DAYS;
        LocalDate from;
        LocalDate to = today;
        switch (preset) {
            case LAST_7_DAYS -> from = today.minusDays(6);
            case LAST_30_DAYS -> from = today.minusDays(29);
            case PREVIOUS_MONTH -> {
                YearMonth previous = YearMonth.from(today).minusMonths(1);
                from = previous.atDay(1);
                to = previous.atEndOfMonth();
            }
            case CURRENT_SUBSCRIPTION -> from = current != null && current.getStartAt() != null
                    ? current.getStartAt().toLocalDate() : today.minusDays(29);
            case ARCHIVE -> from = earliestEntry(userId).orElse(today);
            case CUSTOM -> {
                if (request.from() == null || request.to() == null
                        || request.from().isAfter(request.to())
                        || request.to().isAfter(today)) {
                    throw new VisitHistoryException("INVALID_DATE_RANGE", "error.invalid_date_range");
                }
                from = request.from();
                to = request.to();
            }
            default -> from = today.minusDays(29);
        }
        if (from.isAfter(to)) {
            from = to;
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return new Range(preset, from, to, days);
    }

    private java.util.Optional<LocalDate> earliestEntry(Long userId) {
        LocalDate visit = gymVisitRepository.findFirstByUserIdOrderByCheckedInAtAsc(userId)
                .map(row -> row.getCheckedInAt().toLocalDate())
                .orElse(null);
        LocalDate attempt = attemptRepository.findFirstByUserIdOrderByScannedAtAsc(userId)
                .map(row -> row.getScannedAt().toLocalDate())
                .orElse(null);
        if (visit == null) {
            return java.util.Optional.ofNullable(attempt);
        }
        if (attempt == null) {
            return java.util.Optional.of(visit);
        }
        return java.util.Optional.of(visit.isBefore(attempt) ? visit : attempt);
    }

    // ------------------------------------------------------------------
    // Subscription period summary
    // ------------------------------------------------------------------

    private VisitHistoryResponse.SubscriptionPeriod buildSubscriptionPeriod(Subscription current, LocalDate today) {
        if (current == null || current.getSubscriptionId() == null) {
            return null;
        }
        Long subscriptionId = current.getSubscriptionId();
        EntryMode mode = current.getEntryMode() != null ? current.getEntryMode() : EntryMode.HOME_GYM_PENDING;
        LocalDate periodStart = periodCalculator.periodStart(current.getStartAt(), today);
        LocalDateTime periodEndsAt = periodCalculator.periodEndsAt(current.getStartAt(), periodStart);
        LocalDate periodEnd = periodEndsAt != null ? periodEndsAt.toLocalDate().minusDays(1) : null;

        HomeGymLimits.Snapshot limits = homeGymLimits.snapshot();
        long homeVisits = gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                subscriptionId, periodStart, VisitEntryType.HOME_UNLIMITED);
        long standardVisits = gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                subscriptionId, periodStart, VisitEntryType.STANDARD);

        int homeLimit = limits.homeGymMonthlyLimit();
        int standardLimit = limits.effectiveMonthlyLimit(current.getTotalLimit());
        int used;
        int limit;
        if (mode == EntryMode.HOME_GYM) {
            used = (int) homeVisits;
            limit = homeLimit;
        } else if (mode == EntryMode.STANDARD) {
            used = (int) (homeVisits + standardVisits);
            limit = standardLimit;
        } else {
            used = (int) standardVisits;
            limit = standardLimit;
        }
        int remaining = Math.max(0, limit - used);

        VisitHistoryResponse.HomeGym homeGym = null;
        if (mode == EntryMode.HOME_GYM) {
            SubscriptionHomeGym active = homeGymRepository
                    .findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(subscriptionId, HomeGymStatus.ACTIVE)
                    .orElse(null);
            if (active != null) {
                String name = gymVisitRepository
                        .findFirstBySubscriptionIdAndGymIdAndEntryTypeAndGymNameIsNotNullOrderByCheckedInAtDesc(
                                subscriptionId, active.getGymId(), VisitEntryType.HOME_UNLIMITED)
                        .map(GymVisit::getGymName)
                        .orElseGet(() -> catalogGymName(active.getGymId(), current.getPackageId()));
                homeGym = new VisitHistoryResponse.HomeGym(
                        active.getGymId(), name, (int) homeVisits, homeLimit);
            }
        }

        return new VisitHistoryResponse.SubscriptionPeriod(
                packageName(current.getPackageId()),
                mode.name(),
                periodStart,
                periodEnd,
                used,
                limit,
                remaining,
                homeGym);
    }

    // ------------------------------------------------------------------
    // counterAfter
    // ------------------------------------------------------------------

    private Map<Long, VisitHistoryResponse.CounterAfter> buildCounters(
            List<GymVisit> pageVisits, Map<Long, Subscription> subscriptions) {
        Map<Long, VisitHistoryResponse.CounterAfter> counters = new HashMap<>();
        if (pageVisits.isEmpty()) {
            return counters;
        }
        Map<CounterKey, Boolean> periods = new LinkedHashMap<>();
        for (GymVisit visit : pageVisits) {
            Subscription subscription = subscriptions.get(visit.getSubscriptionId());
            LocalDate periodStart = visit.getPeriodStart();
            if (periodStart == null && subscription != null && subscription.getStartAt() != null) {
                periodStart = periodCalculator.periodStart(
                        subscription.getStartAt(), visit.getCheckedInAt().toLocalDate());
            }
            if (periodStart != null) {
                periods.putIfAbsent(new CounterKey(visit.getSubscriptionId(), periodStart), Boolean.TRUE);
            }
        }

        HomeGymLimits.Snapshot limits = homeGymLimits.snapshot();
        int homeLimit = limits.homeGymMonthlyLimit();
        for (CounterKey key : periods.keySet()) {
            Subscription subscription = subscriptions.get(key.subscriptionId());
            int standardLimit = limits.effectiveMonthlyLimit(
                    subscription != null ? subscription.getTotalLimit() : null);
            long home = 0;
            long total = 0;
            for (GymVisit visit : gymVisitRepository.findCountableForCounter(
                    key.subscriptionId(), key.periodStart())) {
                total++;
                if (visit.getEntryType() == VisitEntryType.HOME_UNLIMITED) {
                    home++;
                    counters.put(visit.getId(),
                            new VisitHistoryResponse.CounterAfter((int) home, homeLimit));
                } else {
                    counters.put(visit.getId(),
                            new VisitHistoryResponse.CounterAfter((int) total, standardLimit));
                }
            }
        }
        return counters;
    }

    // ------------------------------------------------------------------
    // Names, packages, cursor
    // ------------------------------------------------------------------

    private Map<Long, Subscription> loadSubscriptions(Collection<Long> subscriptionIds) {
        if (subscriptionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Subscription> result = new HashMap<>();
        subscriptionRepository.findAllById(subscriptionIds)
                .forEach(subscription -> result.put(subscription.getSubscriptionId(), subscription));
        return result;
    }

    private Map<Long, String> loadPackageNames(Map<Long, Subscription> subscriptions) {
        Set<Long> packageIds = new HashSet<>();
        for (Subscription subscription : subscriptions.values()) {
            if (subscription.getPackageId() != null) {
                packageIds.add(subscription.getPackageId());
            }
        }
        if (packageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> namesById = new HashMap<>();
        packageRepository.findAllById(packageIds)
                .forEach(packageRow -> namesById.put(packageRow.getId(), packageRow.getName()));
        Map<Long, String> result = new HashMap<>();
        subscriptions.forEach((subscriptionId, subscription) ->
                result.put(subscriptionId, namesById.get(subscription.getPackageId())));
        return result;
    }

    private void resolveMissingGymNames(Long userId, Collection<Long> gymIds,
                                        Map<Long, String> names, Long packageId) {
        List<Long> missing = gymIds.stream()
                .filter(Objects::nonNull)
                .filter(gymId -> !names.containsKey(gymId))
                .distinct()
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Map<Long, NameAt> latest = new HashMap<>();
        collectLatestNames(latest, gymVisitRepository.findLatestGymNames(userId, missing));
        collectLatestNames(latest, attemptRepository.findLatestGymNames(userId, missing));
        latest.forEach((gymId, value) -> names.put(gymId, value.name()));
        for (Long gymId : missing) {
            if (!names.containsKey(gymId)) {
                String name = catalogGymName(gymId, packageId);
                if (name != null) {
                    names.put(gymId, name);
                }
            }
        }
    }

    /** Last-resort gym name lookup; catalog may be unreachable, so never propagate nulls. */
    private String catalogGymName(Long gymId, Long packageId) {
        try {
            CatalogServiceGrpcClient.HomeGymInfo info = catalogClient.getHomeGymInfo(gymId, packageId);
            return info != null ? info.name() : null;
        } catch (RuntimeException e) {
            log.warn("Could not resolve gym name for gym {}: {}", gymId, e.getMessage());
            return null;
        }
    }

    private void collectLatestNames(Map<Long, NameAt> target, List<Object[]> rows) {
        for (Object[] row : rows) {
            if (row[0] == null || row[1] == null) {
                continue;
            }
            Long gymId = ((Number) row[0]).longValue();
            String name = String.valueOf(row[1]);
            LocalDateTime at = row[2] instanceof LocalDateTime value ? value : null;
            NameAt current = target.get(gymId);
            if (current == null || (at != null && (current.at() == null || at.isAfter(current.at())))) {
                target.put(gymId, new NameAt(name, at));
            }
        }
    }

    private void mergeGymCounts(Map<Long, Long> target, List<Object[]> rows) {
        for (Object[] row : rows) {
            if (row[0] == null) {
                continue;
            }
            Long gymId = ((Number) row[0]).longValue();
            long count = row[1] instanceof Number number ? number.longValue() : 0L;
            target.merge(gymId, count, Long::sum);
        }
    }

    private String packageName(Long packageId) {
        if (packageId == null) {
            return null;
        }
        return packageRepository.findById(packageId)
                .map(SubscriptionPackage::getName)
                .orElse(null);
    }

    private String rejectText(String code) {
        if (code == null) {
            return null;
        }
        try {
            return messageSource.getMessage("visit.reject." + code, null,
                    LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return code;
        }
    }

    private Cursor parseCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return Cursor.NONE;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 3);
            if (parts.length != 3 || (!VISIT.equals(parts[1]) && !ATTEMPT.equals(parts[1]))) {
                throw new IllegalArgumentException("malformed cursor");
            }
            return new Cursor(LocalDateTime.parse(parts[0]), parts[1], Long.valueOf(parts[2]));
        } catch (Exception e) {
            throw new VisitHistoryException("INVALID_CURSOR", "error.invalid_request");
        }
    }

    private String encodeCursor(Entry entry) {
        String raw = entry.scannedAt() + "|" + entry.kind() + "|" + entry.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private int normaliseLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private record Range(VisitHistoryPreset preset, LocalDate from, LocalDate to, long days) {
    }

    private record CounterKey(Long subscriptionId, LocalDate periodStart) {
    }

    private record NameAt(String name, LocalDateTime at) {
    }

    private record Entry(String kind, Long id, LocalDateTime scannedAt, GymVisit visit, VisitAttempt attempt) {
    }

    private record Cursor(LocalDateTime scannedAt, String kind, Long id) {
        private static final Cursor NONE = new Cursor(null, null, null);
    }
}
