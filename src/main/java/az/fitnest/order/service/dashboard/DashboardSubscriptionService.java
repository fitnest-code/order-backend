package az.fitnest.order.service.dashboard;

import az.fitnest.order.dto.dashboard.DashboardPackageCard;
import az.fitnest.order.dto.dashboard.DashboardSubscriptionListItem;
import az.fitnest.order.dto.dashboard.DashboardSubscriptionPage;
import az.fitnest.order.dto.dashboard.DashboardSubscriptionStartItem;
import az.fitnest.order.dto.dashboard.DashboardSubscriptionSummaryResponse;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardSubscriptionService {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    private static final List<String> ENDED_STATUSES = List.of("FINISHED", "CANCELLED", "EXPIRED");

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPackageRepository subscriptionPackageRepository;

    public DashboardSubscriptionSummaryResponse getSummary(LocalDate dateFrom, LocalDate dateTo) {
        LocalDate[] range = resolveRange(dateFrom, dateTo);
        LocalDateTime start = range[0].atStartOfDay();
        LocalDateTime end = range[1].atTime(LocalTime.of(23, 59, 59));

        long active = subscriptionRepository.countByStatus("ACTIVE");
        long frozen = subscriptionRepository.countByStatus("FROZEN");
        long ended = subscriptionRepository.countByStatusInAndEndAtBetween(ENDED_STATUSES, start, end);
        long renewed = subscriptionRepository.countByIsUpgradedAndStartAtBetween(true, start, end);

        long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(range[0], range[1]) + 1);
        long startedNow = subscriptionRepository.countByStartAtBetween(start, end);
        LocalDateTime prevStart = range[0].minusDays(days).atStartOfDay();
        LocalDateTime prevEnd = range[0].minusDays(1).atTime(LocalTime.of(23, 59, 59));
        long startedPrev = subscriptionRepository.countByStartAtBetween(prevStart, prevEnd);

        Double deltaPct = null;
        if (startedPrev > 0) {
            deltaPct = ((startedNow - startedPrev) * 100.0) / startedPrev;
        }

        return new DashboardSubscriptionSummaryResponse(
                active,
                frozen,
                ended,
                renewed,
                deltaPct,
                startedNow >= startedPrev,
                buildPackageCards(),
                range[0].toString(),
                range[1].toString()
        );
    }

    public DashboardSubscriptionPage list(String status, Long packageId, int page, int size) {
        String normalized = normalizeStatus(status);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        Page<Subscription> result = packageId != null
                ? subscriptionRepository.findByStatusAndPackageId(normalized, packageId, pageable)
                : subscriptionRepository.findByStatus(normalized, pageable);

        Map<Long, String> names = resolvePackageNames(
                result.getContent().stream().map(Subscription::getPackageId).distinct().toList());

        List<DashboardSubscriptionListItem> content = result.getContent().stream()
                .map(s -> toListItem(s, names))
                .toList();

        return new DashboardSubscriptionPage(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    public String exportCsv(String status, Long packageId) {
        String normalized = normalizeStatus(status);
        List<Subscription> rows = packageId != null
                ? subscriptionRepository.findByStatusAndPackageId(normalized, packageId)
                : subscriptionRepository.findByStatus(normalized);

        Map<Long, String> names = resolvePackageNames(
                rows.stream().map(Subscription::getPackageId).distinct().toList());

        StringBuilder sb = new StringBuilder();
        sb.append("subscriptionId,userId,packageId,packageName,status,startAt,endAt,isUpgraded\n");
        for (Subscription s : rows) {
            sb.append(s.getSubscriptionId()).append(',')
                    .append(s.getUserId()).append(',')
                    .append(s.getPackageId()).append(',')
                    .append(csv(names.getOrDefault(s.getPackageId(), ""))).append(',')
                    .append(csv(s.getStatus())).append(',')
                    .append(s.getStartAt() != null ? s.getStartAt() : "").append(',')
                    .append(s.getEndAt() != null ? s.getEndAt() : "").append(',')
                    .append(Boolean.TRUE.equals(s.getIsUpgraded())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Latest ACTIVE/FROZEN startAt per user — purchaseDate for dashboard customers list.
     */
    public List<DashboardSubscriptionStartItem> purchaseDates(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Subscription> subs = subscriptionRepository.findByUserIdInAndStatusIn(
                userIds, List.of("ACTIVE", "FROZEN"));
        Map<Long, Subscription> latest = new LinkedHashMap<>();
        for (Subscription s : subs) {
            Subscription existing = latest.get(s.getUserId());
            if (existing == null
                    || (s.getStartAt() != null && existing.getStartAt() != null
                    && s.getStartAt().isAfter(existing.getStartAt()))) {
                latest.put(s.getUserId(), s);
            }
        }
        return latest.values().stream()
                .map(s -> new DashboardSubscriptionStartItem(
                        s.getUserId(),
                        s.getStartAt() != null ? s.getStartAt().toString() : null,
                        s.getStatus(),
                        s.getPackageId()))
                .toList();
    }

    private List<DashboardPackageCard> buildPackageCards() {
        List<Object[]> rows = subscriptionRepository.countGroupedByPackageAndStatus(
                List.of("ACTIVE", "FROZEN"));
        Map<Long, long[]> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long packageId = (Long) row[0];
            String status = (String) row[1];
            long count = row[2] instanceof Number n ? n.longValue() : 0L;
            long[] pair = counts.computeIfAbsent(packageId, id -> new long[2]);
            if ("ACTIVE".equalsIgnoreCase(status)) {
                pair[0] = count;
            } else if ("FROZEN".equalsIgnoreCase(status)) {
                pair[1] = count;
            }
        }
        Map<Long, String> names = resolvePackageNames(new ArrayList<>(counts.keySet()));
        List<DashboardPackageCard> cards = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : counts.entrySet()) {
            cards.add(new DashboardPackageCard(
                    e.getKey(),
                    names.getOrDefault(e.getKey(), "Package " + e.getKey()),
                    e.getValue()[0],
                    e.getValue()[1]
            ));
        }
        return cards;
    }

    private Map<Long, String> resolvePackageNames(List<Long> packageIds) {
        Map<Long, String> names = new HashMap<>();
        if (packageIds == null || packageIds.isEmpty()) {
            return names;
        }
        for (SubscriptionPackage pkg : subscriptionPackageRepository.findAllById(packageIds)) {
            names.put(pkg.getId(), pkg.getName());
        }
        return names;
    }

    private static DashboardSubscriptionListItem toListItem(Subscription s, Map<Long, String> names) {
        return new DashboardSubscriptionListItem(
                s.getSubscriptionId(),
                s.getUserId(),
                s.getPackageId(),
                names.getOrDefault(s.getPackageId(), "Package " + s.getPackageId()),
                s.getStatus(),
                s.getStartAt() != null ? s.getStartAt().toString() : null,
                s.getEndAt() != null ? s.getEndAt().toString() : null,
                s.getIsUpgraded()
        );
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ACTIVE";
        }
        String upper = status.trim().toUpperCase();
        if (!"ACTIVE".equals(upper) && !"FROZEN".equals(upper)) {
            throw new IllegalArgumentException("status must be ACTIVE or FROZEN");
        }
        return upper;
    }

    private static LocalDate[] resolveRange(LocalDate dateFrom, LocalDate dateTo) {
        LocalDate today = LocalDate.now(BAKU);
        LocalDate to = dateTo != null ? dateTo : today;
        LocalDate from = dateFrom != null ? dateFrom : to.minusDays(29);
        if (from.isAfter(to)) {
            return new LocalDate[]{to, from};
        }
        return new LocalDate[]{from, to};
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
