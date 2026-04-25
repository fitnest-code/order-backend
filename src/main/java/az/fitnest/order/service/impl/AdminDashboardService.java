package az.fitnest.order.service.impl;

import az.fitnest.order.client.dashboard.CustomerDashboardClient;
import az.fitnest.order.client.dashboard.GymDashboardClient;
import az.fitnest.order.client.dashboard.PaymentDashboardClient;
import az.fitnest.order.dto.dashboard.ChartPointDto;
import az.fitnest.order.dto.dashboard.CustomerGrowthResponseDto;
import az.fitnest.order.dto.dashboard.CustomerGrowthSeriesDto;
import az.fitnest.order.dto.dashboard.FilterOptionDto;
import az.fitnest.order.dto.dashboard.FilterResponseDto;
import az.fitnest.order.dto.dashboard.KpiCardDto;
import az.fitnest.order.dto.dashboard.RevenueResponseDto;
import az.fitnest.order.dto.dashboard.RevenueSeriesDto;
import az.fitnest.order.dto.dashboard.RevenueTotalsDto;
import az.fitnest.order.dto.dashboard.SummaryResponseDto;
import az.fitnest.order.exception.DashboardValidationException;
import az.fitnest.order.exception.ForbiddenException;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final Locale AZ_LOCALE = Locale.forLanguageTag("az");
    private static final List<String> ACTIVE_STATUSES = List.of("ACTIVE", "FROZEN", "NO_LIMITS");

    private final SubscriptionRepository subscriptionRepository;
    private final GymVisitRepository gymVisitRepository;
    private final CustomerDashboardClient customerDashboardClient;
    private final GymDashboardClient gymDashboardClient;
    private final PaymentDashboardClient paymentDashboardClient;

    @Cacheable(cacheNames = "dashboard-filters")
    public FilterResponseDto getFilters(String roleScope, HttpServletRequest request) {
        validateRoleScope(roleScope, request);
        return new FilterResponseDto(
                List.of(
                        new FilterOptionDto("bronze", "Bronze"),
                        new FilterOptionDto("silver", "Silver"),
                        new FilterOptionDto("gold", "Gold"),
                        new FilterOptionDto("platinum", "Platinum")
                ),
                List.of(
                        new FilterOptionDto("daily", "Günlük"),
                        new FilterOptionDto("weekly", "Həftəlik"),
                        new FilterOptionDto("monthly", "Aylıq"),
                        new FilterOptionDto("yearly", "İllik")
                ),
                List.of(
                        new FilterOptionDto("last_7_days", "Son 7 gün"),
                        new FilterOptionDto("last_30_days", "Son 1 ay"),
                        new FilterOptionDto("last_365_days", "Son 1 il")
                ),
                Map.of(
                        "package", "bronze",
                        "period", "monthly",
                        "time_range", "last_30_days"
                )
        );
    }

    @Cacheable(cacheNames = "dashboard-summary",
            key = "'summary:' + #timeRange + ':' + (#gymId != null ? #gymId : (#request.getHeader('X-Gym-Id') != null ? #request.getHeader('X-Gym-Id') : 'null'))")
    public SummaryResponseDto getSummary(String timeRange, Long gymId, HttpServletRequest request) {
        TimeRange range = TimeRange.from(timeRange, "time_range");
        Long effectiveGymId = resolveGymId(gymId, request);
        String authHeader = extractAuthHeader(request);

        PeriodWindow current = PeriodWindow.forLastDays(range.days());
        PeriodWindow previous = current.previous();

        long activeCustomers = subscriptionRepository.countDistinctUsersByStatusAndPeriod(
                ACTIVE_STATUSES, current.from(), current.to(), effectiveGymId);
        long previousActiveCustomers = subscriptionRepository.countDistinctUsersByStatusAndPeriod(
                ACTIVE_STATUSES, previous.from(), previous.to(), effectiveGymId);

        try {
            var remote = customerDashboardClient.getSummary(effectiveGymId, authHeader);
            if (remote.isPresent()) {
                activeCustomers = remote.get().activeCustomers();
                previousActiveCustomers = remote.get().previousPeriodCustomers();
            }
        } catch (RuntimeException ex) {
            log.debug("Customer summary fallback to local: {}", ex.getMessage());
        }

        long partners = gymVisitRepository.countDistinctGymsByPeriod(current.from(), current.to(), effectiveGymId);
        long previousPartners = gymVisitRepository.countDistinctGymsByPeriod(previous.from(), previous.to(), effectiveGymId);
        try {
            var remoteGym = gymDashboardClient.getPartnerCount(authHeader);
            if (remoteGym.isPresent()) {
                partners = remoteGym.get().activeGyms();
                previousPartners = remoteGym.get().previousPeriodGyms();
            }
        } catch (RuntimeException ex) {
            log.debug("Gym summary fallback to local: {}", ex.getMessage());
        }

        long activeSubscriptions = subscriptionRepository.countByStatusAndPeriod(
                ACTIVE_STATUSES, current.from(), current.to(), effectiveGymId);
        long previousActiveSubscriptions = subscriptionRepository.countByStatusAndPeriod(
                ACTIVE_STATUSES, previous.from(), previous.to(), effectiveGymId);

        long qrScans = gymVisitRepository.countByPeriod(current.from(), current.to(), effectiveGymId);
        long previousQrScans = gymVisitRepository.countByPeriod(previous.from(), previous.to(), effectiveGymId);

        return new SummaryResponseDto(
                List.of(
                        new KpiCardDto("active_customers", "Aktiv Müştərilər", activeCustomers,
                                calculateDelta(activeCustomers, previousActiveCustomers), range.periodLabel()),
                        new KpiCardDto("partners", "Partnyorlar", partners,
                                calculateDelta(partners, previousPartners), range.periodLabel()),
                        new KpiCardDto("active_subscriptions", "Aktiv Abunəliklər", activeSubscriptions,
                                calculateDelta(activeSubscriptions, previousActiveSubscriptions), range.periodLabel()),
                        new KpiCardDto("qr_scans", "QR oxunma", qrScans,
                                calculateDelta(qrScans, previousQrScans), range.periodLabel())
                ),
                Instant.now()
        );
    }

    @Cacheable(cacheNames = "dashboard-revenue",
            key = "'revenue:' + #packageCode + ':' + #period + ':' + (#dateFrom != null ? #dateFrom : 'null') + ':' + (#dateTo != null ? #dateTo : 'null') + ':' + (#gymId != null ? #gymId : (#request.getHeader('X-Gym-Id') != null ? #request.getHeader('X-Gym-Id') : 'null'))")
    public RevenueResponseDto getRevenue(String packageCode, String period, LocalDate dateFrom, LocalDate dateTo,
                                         Long gymId, HttpServletRequest request) {
        String resolvedPackage = validatePackage(packageCode);
        PeriodType periodType = PeriodType.from(period, "period");
        validateDates(dateFrom, dateTo);
        Long effectiveGymId = resolveGymId(gymId, request);
        String authHeader = extractAuthHeader(request);

        try {
            var remoteRevenue = paymentDashboardClient.getRevenue(
                    resolvedPackage, periodType.code(), dateFrom, dateTo, effectiveGymId, authHeader
            );
            if (remoteRevenue.isPresent() && remoteRevenue.get().dataPoints() != null) {
                List<ChartPointDto> points = remoteRevenue.get().dataPoints().stream()
                        .filter(p -> "SUCCESS".equalsIgnoreCase(p.status()))
                        .map(p -> new ChartPointDto(formatLabel(parseDate(p.timestamp()), periodType), p.amount()))
                        .toList();
                if (!points.isEmpty()) {
                    double total = points.stream().mapToDouble(p -> p.value().doubleValue()).sum();
                    String currency = remoteRevenue.get().currency() == null || remoteRevenue.get().currency().isBlank()
                            ? "AZN"
                            : remoteRevenue.get().currency();
                    return new RevenueResponseDto(
                            new RevenueSeriesDto("revenue", currency, resolvedPackage, periodType.code(), points),
                            new RevenueTotalsDto(total)
                    );
                }
            }
        } catch (RuntimeException ex) {
            log.debug("Payment revenue fallback to local: {}", ex.getMessage());
        }

        List<PeriodWindow> windows = buildWindows(periodType, dateFrom, dateTo);
        List<ChartPointDto> points = new ArrayList<>();
        double total = 0.0;
        for (PeriodWindow window : windows) {
            BigDecimal amount = subscriptionRepository.sumRevenueByPeriod(
                    ACTIVE_STATUSES, window.from(), window.to(), resolvedPackage, effectiveGymId);
            double value = amount == null ? 0.0 : amount.doubleValue();
            total += value;
            points.add(new ChartPointDto(formatLabel(window.from().toLocalDate(), periodType), value));
        }

        return new RevenueResponseDto(
                new RevenueSeriesDto("revenue", "AZN", resolvedPackage, periodType.code(), points),
                new RevenueTotalsDto(total)
        );
    }

    @Cacheable(cacheNames = "dashboard-growth",
            key = "'growth:' + #period + ':' + #metric + ':' + (#dateFrom != null ? #dateFrom : 'null') + ':' + (#dateTo != null ? #dateTo : 'null') + ':' + (#gymId != null ? #gymId : (#request.getHeader('X-Gym-Id') != null ? #request.getHeader('X-Gym-Id') : 'null'))")
    public CustomerGrowthResponseDto getCustomerGrowth(String period, String metric, LocalDate dateFrom, LocalDate dateTo,
                                                       Long gymId, HttpServletRequest request) {
        PeriodType periodType = PeriodType.from(period, "period");
        MetricType metricType = MetricType.from(metric, "metric");
        validateDates(dateFrom, dateTo);
        Long effectiveGymId = resolveGymId(gymId, request);
        String authHeader = extractAuthHeader(request);

        try {
            var remoteGrowth = customerDashboardClient.getGrowth(metricType.code(), periodType.code(), dateFrom, dateTo,
                    effectiveGymId, authHeader);
            if (remoteGrowth.isPresent() && remoteGrowth.get().dataPoints() != null && !remoteGrowth.get().dataPoints().isEmpty()) {
                List<ChartPointDto> points = remoteGrowth.get().dataPoints().stream()
                        .map(dp -> new ChartPointDto(formatLabel(parseDate(dp.timestamp()), periodType), dp.count()))
                        .toList();
                return new CustomerGrowthResponseDto(new CustomerGrowthSeriesDto(metricType.code(), periodType.code(), points));
            }
        } catch (RuntimeException ex) {
            log.debug("Customer growth fallback to local: {}", ex.getMessage());
        }

        List<PeriodWindow> windows = buildWindows(periodType, dateFrom, dateTo);
        List<ChartPointDto> points = new ArrayList<>();
        for (PeriodWindow window : windows) {
            long value = switch (metricType) {
                case NEW_CUSTOMERS -> subscriptionRepository.countFirstTimeCustomersByPeriod(
                        window.from(), window.to(), effectiveGymId);
                case ACTIVE_CUSTOMERS -> subscriptionRepository.countDistinctUsersByStatusAndPeriod(
                        ACTIVE_STATUSES, window.from(), window.to(), effectiveGymId);
            };
            points.add(new ChartPointDto(formatLabel(window.from().toLocalDate(), periodType), value));
        }

        return new CustomerGrowthResponseDto(
                new CustomerGrowthSeriesDto(metricType.code(), periodType.code(), points)
        );
    }

    private Long resolveGymId(Long requestedGymId, HttpServletRequest request) {
        if (!isGymOwner(request)) {
            return requestedGymId;
        }
        Long tokenGymId = parseLongHeader(request, "X-Gym-Id");
        if (tokenGymId == null) {
            throw new DashboardValidationException("gym_id", "is required for gym_owner");
        }
        if (requestedGymId != null && !requestedGymId.equals(tokenGymId)) {
            throw new ForbiddenException("You do not have permission to access data for gym_id " + requestedGymId + ".");
        }
        return tokenGymId;
    }

    private boolean isGymOwner(HttpServletRequest request) {
        String roles = String.join(" ",
                valueOrEmpty(request.getHeader("X-Scopes")),
                valueOrEmpty(request.getHeader("X-User-Roles")));
        return roles.toLowerCase(Locale.ROOT).contains("gym_owner");
    }

    private Long parseLongHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new DashboardValidationException(name, "must be a valid integer");
        }
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String extractAuthHeader(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return auth == null ? "" : auth;
    }

    private void validateRoleScope(String roleScope, HttpServletRequest request) {
        if (roleScope == null || roleScope.isBlank()) {
            return;
        }
        String normalized = roleScope.toLowerCase(Locale.ROOT);
        if (!List.of("gym_owner", "all").contains(normalized)) {
            throw new DashboardValidationException("role_scope", "must be one of: gym_owner, all");
        }
        if ("gym_owner".equals(normalized) && !isGymOwner(request)) {
            throw new DashboardValidationException("role_scope", "gym_owner scope is only available for gym_owner role");
        }
    }

    private String validatePackage(String packageCode) {
        String value = packageCode == null || packageCode.isBlank() ? "bronze" : packageCode.toLowerCase(Locale.ROOT);
        if (!List.of("bronze", "silver", "gold", "platinum", "all").contains(value)) {
            throw new DashboardValidationException("package", "must be one of: bronze, silver, gold, platinum, all");
        }
        return value;
    }

    private void validateDates(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new DashboardValidationException(List.of(
                    new DashboardValidationException.FieldIssue("date_from", "must be <= date_to"),
                    new DashboardValidationException.FieldIssue("date_to", "must be >= date_from")
            ));
        }
    }

    private List<PeriodWindow> buildWindows(PeriodType period, LocalDate dateFrom, LocalDate dateTo) {
        LocalDate start = dateFrom != null ? dateFrom : defaultStart(period);
        LocalDate end = dateTo != null ? dateTo : LocalDate.now(ZoneOffset.UTC);
        List<PeriodWindow> windows = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate next = nextBoundary(cursor, period);
            LocalDate cappedNext = next.isAfter(end.plusDays(1)) ? end.plusDays(1) : next;
            windows.add(new PeriodWindow(cursor.atStartOfDay(), cappedNext.atStartOfDay()));
            cursor = cappedNext;
        }
        return windows;
    }

    private LocalDate defaultStart(PeriodType period) {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        return switch (period) {
            case DAILY -> now.minusDays(30);
            case WEEKLY -> now.minusWeeks(12);
            case MONTHLY -> now.minusMonths(11).withDayOfMonth(1);
            case YEARLY -> now.minusYears(4).withDayOfYear(1);
        };
    }

    private LocalDate nextBoundary(LocalDate date, PeriodType period) {
        return switch (period) {
            case DAILY -> date.plusDays(1);
            case WEEKLY -> date.plusWeeks(1);
            case MONTHLY -> YearMonth.from(date).plusMonths(1).atDay(1);
            case YEARLY -> date.plusYears(1).withDayOfYear(1);
        };
    }

    private String formatLabel(LocalDate date, PeriodType period) {
        if (date == null) {
            return "";
        }
        return switch (period) {
            case DAILY -> date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            case WEEKLY -> "Həftə " + date.get(WeekFields.ISO.weekOfWeekBasedYear());
            case MONTHLY -> capitalize(date.getMonth().getDisplayName(TextStyle.FULL, AZ_LOCALE));
            case YEARLY -> String.valueOf(date.getYear());
        };
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(AZ_LOCALE) + value.substring(1);
    }

    private double calculateDelta(long currentValue, long previousValue) {
        if (previousValue == 0) {
            return 0.0;
        }
        double delta = ((double) (currentValue - previousValue) / previousValue) * 100;
        return Math.round(delta * 10.0) / 10.0;
    }

    private record PeriodWindow(LocalDateTime from, LocalDateTime to) {
        static PeriodWindow forLastDays(int days) {
            LocalDateTime to = LocalDateTime.now(ZoneOffset.UTC);
            return new PeriodWindow(to.minusDays(days), to);
        }

        PeriodWindow previous() {
            long seconds = java.time.Duration.between(from, to).getSeconds();
            return new PeriodWindow(from.minusSeconds(seconds), from);
        }
    }

    private enum TimeRange {
        LAST_7_DAYS("last_7_days", "Son 7 gün", "son 7 gün", 7),
        LAST_30_DAYS("last_30_days", "Son 1 ay", "son 1 ay", 30),
        LAST_365_DAYS("last_365_days", "Son 1 il", "son 1 il", 365);

        private final String code;
        private final String title;
        private final String periodLabel;
        private final int days;

        TimeRange(String code, String title, String periodLabel, int days) {
            this.code = code;
            this.title = title;
            this.periodLabel = periodLabel;
            this.days = days;
        }

        static TimeRange from(String code, String fieldName) {
            String value = code == null || code.isBlank() ? "last_30_days" : code;
            for (TimeRange range : values()) {
                if (range.code.equalsIgnoreCase(value)) {
                    return range;
                }
            }
            throw new DashboardValidationException(fieldName, "must be one of: last_7_days, last_30_days, last_365_days");
        }

        int days() {
            return days;
        }

        String periodLabel() {
            return periodLabel;
        }
    }

    private enum PeriodType {
        DAILY("daily"),
        WEEKLY("weekly"),
        MONTHLY("monthly"),
        YEARLY("yearly");

        private final String code;

        PeriodType(String code) {
            this.code = code;
        }

        static PeriodType from(String code, String fieldName) {
            String value = code == null || code.isBlank() ? "monthly" : code;
            for (PeriodType period : values()) {
                if (period.code.equalsIgnoreCase(value)) {
                    return period;
                }
            }
            throw new DashboardValidationException(fieldName, "must be one of: daily, weekly, monthly, yearly");
        }

        String code() {
            return code;
        }
    }

    private enum MetricType {
        NEW_CUSTOMERS("new_customers"),
        ACTIVE_CUSTOMERS("active_customers");

        private final String code;

        MetricType(String code) {
            this.code = code;
        }

        static MetricType from(String code, String fieldName) {
            String value = code == null || code.isBlank() ? "new_customers" : code;
            for (MetricType metric : values()) {
                if (metric.code.equalsIgnoreCase(value)) {
                    return metric;
                }
            }
            throw new DashboardValidationException(fieldName, "must be one of: new_customers, active_customers");
        }

        String code() {
            return code;
        }
    }
}
