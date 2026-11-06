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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Read-model tests of {@code GET /api/v1/me/visits/history} (spec H-01 ... H-09).
 *
 * <p>Pure Mockito: repositories are stubbed page by page, so these tests pin the
 * service contract (ranges, merge order, filters, counters, cursor) without a database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisitHistoryServiceTest {

    private static final long USER = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionPackageRepository packageRepository;
    @Mock private SubscriptionHomeGymRepository homeGymRepository;
    @Mock private GymVisitRepository gymVisitRepository;
    @Mock private VisitAttemptRepository attemptRepository;
    @Mock private CurrentSubscriptionResolver subscriptionResolver;
    @Mock private HomeGymLimits homeGymLimits;
    @Mock private CatalogServiceGrpcClient catalogClient;
    @Mock private MessageSource messageSource;

    private final HomeGymPeriodCalculator periodCalculator = new HomeGymPeriodCalculator() {
        @Override
        public LocalDateTime now() {
            return NOW;
        }
    };

    private VisitHistoryService service;

    @BeforeEach
    void setUp() {
        service = new VisitHistoryService(
                subscriptionRepository,
                packageRepository,
                homeGymRepository,
                gymVisitRepository,
                attemptRepository,
                subscriptionResolver,
                periodCalculator,
                homeGymLimits,
                catalogClient,
                messageSource);

        when(homeGymLimits.snapshot()).thenReturn(new HomeGymLimits.Snapshot(30, 1, 12, 12));
        when(messageSource.getMessage(anyString(), nullable(Object[].class), any()))
                .thenAnswer(invocation -> "text:" + invocation.getArgument(0));
        when(gymVisitRepository.countHistoryByGym(any(), any(), any(), anyString()))
                .thenReturn(Collections.emptyList());
        when(attemptRepository.countHistoryByGym(any(), any(), any(), anyString()))
                .thenReturn(Collections.emptyList());
        when(gymVisitRepository.findLatestGymNames(any(), any())).thenReturn(Collections.emptyList());
        when(attemptRepository.findLatestGymNames(any(), any())).thenReturn(Collections.emptyList());
        when(gymVisitRepository.findCountableForCounter(any(), any())).thenReturn(Collections.emptyList());
    }

    // ------------------------------------------------------------------
    // H-01: default query = last 30 days, ALL filters, both sources merged
    // ------------------------------------------------------------------

    @Test
    void defaultsToLast30DaysAndMergesVisitsWithAttempts() {
        Subscription subscription = subscription(50L, 3L, EntryMode.STANDARD, 12);
        when(subscriptionResolver.resolve(USER)).thenReturn(subscription);

        GymVisit visit = visit(1L, 10L, "Alpha", LocalDateTime.of(2026, 11, 1, 18, 0),
                VisitEntryType.STANDARD, subscription);
        VisitAttempt attempt = attempt(2L, 11L, "Beta", LocalDateTime.of(2026, 11, 2, 9, 0),
                "TIER_NOT_ALLOWED_AT_THIS_TIME", "STANDARD", subscription);

        when(gymVisitRepository.findHistory(eq(USER), any(), any(), eq("ALL"), eq(false), eq(0L),
                eq(false), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(visit));
        when(attemptRepository.findHistory(eq(USER), any(), any(), eq("ALL"), eq(false), eq(0L),
                eq(false), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(attempt));
        when(gymVisitRepository.countHistory(eq(USER), any(), any(), eq("ALL"))).thenReturn(1L);
        when(attemptRepository.countHistory(eq(USER), any(), any(), eq("ALL"))).thenReturn(1L);
        when(gymVisitRepository.countHistoryByGym(eq(USER), any(), any(), eq("ALL")))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 1L}));
        when(attemptRepository.countHistoryByGym(eq(USER), any(), any(), eq("ALL")))
                .thenReturn(List.<Object[]>of(new Object[]{11L, 1L}));
        when(gymVisitRepository.findCountableForCounter(50L, LocalDate.of(2026, 10, 5)))
                .thenReturn(List.of(visit));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                50L, LocalDate.of(2026, 10, 5), VisitEntryType.STANDARD)).thenReturn(1L);
        when(subscriptionRepository.findAllById(any())).thenReturn(List.of(subscription));
        when(packageRepository.findAllById(any())).thenReturn(List.of(packageRow(3L, "Silver")));

        VisitHistoryResponse response = service.getHistory(USER, query(
                null, null, null, null, null, null, null, 20));

        assertThat(response.range().preset()).isEqualTo("LAST_30_DAYS");
        assertThat(response.range().from()).isEqualTo(TODAY.minusDays(29));
        assertThat(response.range().to()).isEqualTo(TODAY);
        assertThat(response.range().days()).isEqualTo(30);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).type()).isEqualTo("ATTEMPT");
        assertThat(response.items().get(0).status()).isEqualTo("REJECTED");
        assertThat(response.items().get(0).rejectReason().code())
                .isEqualTo("TIER_NOT_ALLOWED_AT_THIS_TIME");
        assertThat(response.items().get(0).rejectReason().text())
                .isEqualTo("text:visit.reject.TIER_NOT_ALLOWED_AT_THIS_TIME");
        assertThat(response.items().get(0).counterAfter()).isNull();
        assertThat(response.items().get(1).type()).isEqualTo("VISIT");
        assertThat(response.items().get(1).status()).isEqualTo("SUCCESS");
        assertThat(response.items().get(1).counterAfter().used()).isEqualTo(1);
        assertThat(response.items().get(1).counterAfter().limit()).isEqualTo(12);
        // Both rows resolve the package of their own subscription.
        assertThat(response.items()).extracting(VisitHistoryResponse.Item::packageName)
                .containsOnly("Silver");

        assertThat(response.rangeTotals().success()).isEqualTo(1);
        assertThat(response.rangeTotals().rejected()).isEqualTo(1);
        assertThat(response.rangeTotals().distinctGyms()).isEqualTo(2);
        assertThat(response.gymsInRange()).hasSize(2);
        assertThat(response.nextCursor()).isNull();

        assertThat(response.subscriptionPeriod().periodStart()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(response.subscriptionPeriod().periodEnd()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(response.subscriptionPeriod().used()).isEqualTo(1);
        assertThat(response.subscriptionPeriod().limit()).isEqualTo(12);
        assertThat(response.subscriptionPeriod().remaining()).isEqualTo(11);
    }

    // ------------------------------------------------------------------
    // H-02: PREVIOUS_MONTH on 2 November -> whole October
    // ------------------------------------------------------------------

    @Test
    void previousMonthPresetCoversWholePreviousCalendarMonth() {
        when(subscriptionResolver.resolve(USER)).thenReturn(null);

        VisitHistoryResponse response = service.getHistory(USER, query(
                VisitHistoryPreset.PREVIOUS_MONTH, null, null, null, null, null, null, 20));

        assertThat(response.range().from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(response.range().to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(response.range().days()).isEqualTo(31);
        assertThat(response.subscriptionPeriod()).isNull();
    }

    // ------------------------------------------------------------------
    // H-03: CUSTOM with a future "to" (or inverted range) is rejected
    // ------------------------------------------------------------------

    @Test
    void customRangeRejectsDatesAfterToday() {
        when(subscriptionResolver.resolve(USER)).thenReturn(null);

        assertThatThrownBy(() -> service.getHistory(USER, query(
                VisitHistoryPreset.CUSTOM,
                TODAY.minusDays(3), TODAY.plusDays(1), null, null, null, null, 20)))
                .isInstanceOf(VisitHistoryException.class)
                .extracting(error -> ((VisitHistoryException) error).getErrorCode())
                .isEqualTo("INVALID_DATE_RANGE");

        assertThatThrownBy(() -> service.getHistory(USER, query(
                VisitHistoryPreset.CUSTOM,
                TODAY, TODAY.minusDays(1), null, null, null, null, 20)))
                .isInstanceOf(VisitHistoryException.class);
    }

    // ------------------------------------------------------------------
    // H-04: two-year-old custom range returns entries of an old subscription
    //       and each row carries the package of its own subscription
    // ------------------------------------------------------------------

    @Test
    void customRangeReturnsOldSubscriptionEntriesWithTheirOwnPackage() {
        Subscription current = subscription(51L, 3L, EntryMode.STANDARD, 12);
        Subscription old = subscription(40L, 5L, EntryMode.STANDARD, 12);
        when(subscriptionResolver.resolve(USER)).thenReturn(current);

        LocalDate from = LocalDate.of(2024, 10, 1);
        LocalDate to = LocalDate.of(2024, 10, 31);
        GymVisit oldVisit = visit(9L, 12L, "Gamma", LocalDateTime.of(2024, 10, 15, 8, 0),
                VisitEntryType.STANDARD, old);

        when(gymVisitRepository.findHistory(eq(USER), any(), any(), eq("ALL"), eq(false), eq(0L),
                eq(false), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(oldVisit));
        when(attemptRepository.findHistory(any(), any(), any(), anyString(), anyBoolean(),
                any(), anyBoolean(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(subscriptionRepository.findAllById(any())).thenReturn(List.of(current, old));
        when(packageRepository.findAllById(any()))
                .thenReturn(List.of(packageRow(3L, "Silver"), packageRow(5L, "Bronze")));
        when(packageRepository.findById(3L)).thenReturn(java.util.Optional.of(packageRow(3L, "Silver")));

        VisitHistoryResponse response = service.getHistory(USER, query(
                VisitHistoryPreset.CUSTOM, from, to, null, null, null, null, 20));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).packageName()).isEqualTo("Bronze");
        assertThat(response.items().get(0).gymName()).isEqualTo("Gamma");
    }

    // ------------------------------------------------------------------
    // H-05: a rejected attempt never changes the counters
    // ------------------------------------------------------------------

    @Test
    void rejectedAttemptHasNoCounterAndKeepsSubscriptionLimit() {
        Subscription subscription = subscription(50L, 3L, EntryMode.STANDARD, 12);
        when(subscriptionResolver.resolve(USER)).thenReturn(subscription);
        VisitAttempt attempt = attempt(4L, 11L, "Health Fit", LocalDateTime.of(2026, 11, 2, 17, 20),
                "TIER_NOT_ALLOWED_AT_THIS_TIME", "STANDARD", subscription);
        when(attemptRepository.findHistory(eq(USER), any(), any(), eq("ALL"), eq(false), eq(0L),
                eq(false), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(attempt));
        when(packageRepository.findAllById(any())).thenReturn(List.of(packageRow(3L, "Silver")));

        VisitHistoryResponse response = service.getHistory(USER, query(
                null, null, null, null, null, null, null, 20));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).counterAfter()).isNull();
        assertThat(response.subscriptionPeriod().limit()).isEqualTo(12);
        assertThat(response.subscriptionPeriod().used()).isZero();
    }

    // ------------------------------------------------------------------
    // Cursor paging (H-06 / "next page must not repeat rows")
    // ------------------------------------------------------------------

    @Test
    void cursorReturnsEachRowOnce() {
        Subscription subscription = subscription(50L, 3L, EntryMode.STANDARD, 12);
        when(subscriptionResolver.resolve(USER)).thenReturn(subscription);
        GymVisit newest = visit(3L, 10L, "Alpha", LocalDateTime.of(2026, 11, 1, 20, 0),
                VisitEntryType.STANDARD, subscription);
        GymVisit middle = visit(2L, 10L, "Alpha", LocalDateTime.of(2026, 11, 1, 12, 0),
                VisitEntryType.STANDARD, subscription);
        GymVisit oldest = visit(1L, 10L, "Alpha", LocalDateTime.of(2026, 11, 1, 8, 0),
                VisitEntryType.STANDARD, subscription);

        when(gymVisitRepository.findHistory(eq(USER), any(), any(), eq("ALL"), eq(false), eq(0L),
                anyBoolean(), any(), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> Boolean.TRUE.equals(invocation.getArgument(6))
                        ? List.of(oldest)
                        : List.of(newest, middle, oldest));

        VisitHistoryResponse first = service.getHistory(USER, query(
                null, null, null, null, null, null, null, 2));
        assertThat(first.items()).extracting(VisitHistoryResponse.Item::id)
                .containsExactly("v_3", "v_2");
        assertThat(first.nextCursor()).isNotBlank();

        VisitHistoryResponse second = service.getHistory(USER, query(
                null, null, null, null, null, null, first.nextCursor(), 2));
        assertThat(second.items()).extracting(VisitHistoryResponse.Item::id)
                .containsExactly("v_1");
        assertThat(second.nextCursor()).isNull();
    }

    // ------------------------------------------------------------------
    // H-07: status=REJECTED filters rows but not the range summary
    // ------------------------------------------------------------------

    @Test
    void rejectedFilterKeepsRangeTotals() {
        Subscription subscription = subscription(50L, 3L, EntryMode.STANDARD, 12);
        when(subscriptionResolver.resolve(USER)).thenReturn(subscription);
        VisitAttempt attempt = attempt(2L, 11L, "Beta", LocalDateTime.of(2026, 11, 2, 9, 0),
                "GYM_NOT_IN_PACKAGE", "STANDARD", subscription);
        when(attemptRepository.findHistory(eq(USER), any(), any(), eq("ALL"), eq(false), eq(0L),
                eq(false), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(attempt));
        when(gymVisitRepository.countHistory(eq(USER), any(), any(), eq("ALL"))).thenReturn(3L);
        when(attemptRepository.countHistory(eq(USER), any(), any(), eq("ALL"))).thenReturn(1L);
        when(packageRepository.findAllById(any())).thenReturn(List.of(packageRow(3L, "Silver")));

        VisitHistoryResponse response = service.getHistory(USER, query(
                null, null, null, VisitHistoryStatus.REJECTED, null, null, null, 20));

        assertThat(response.items()).extracting(VisitHistoryResponse.Item::status)
                .containsOnly("REJECTED");
        assertThat(response.rangeTotals().success()).isEqualTo(3);
        assertThat(response.rangeTotals().rejected()).isEqualTo(1);
        verify(gymVisitRepository, never()).findHistory(any(), any(), any(), anyString(), anyBoolean(),
                any(), anyBoolean(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // H-08: mode=HOME_GYM keeps only home-gym visits and reports 7 / 30
    // ------------------------------------------------------------------

    @Test
    void homeGymModeFiltersRowsAndReportsHomeUsage() {
        Subscription subscription = subscription(50L, 3L, EntryMode.HOME_GYM, 30);
        when(subscriptionResolver.resolve(USER)).thenReturn(subscription);
        SubscriptionHomeGym active = new SubscriptionHomeGym();
        active.setSubscriptionId(50L);
        active.setGymId(10L);
        active.setStatus(HomeGymStatus.ACTIVE);
        when(homeGymRepository.findFirstBySubscriptionIdAndStatusOrderBySelectedAtDesc(
                50L, HomeGymStatus.ACTIVE)).thenReturn(java.util.Optional.of(active));

        GymVisit home = visit(6L, 10L, "Alpha", LocalDateTime.of(2026, 11, 1, 18, 0),
                VisitEntryType.HOME_UNLIMITED, subscription);
        when(gymVisitRepository.findHistory(eq(USER), any(), any(), eq("HOME_GYM"), eq(false), eq(0L),
                eq(false), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(home));
        when(gymVisitRepository.findFirstBySubscriptionIdAndGymIdAndEntryTypeAndGymNameIsNotNullOrderByCheckedInAtDesc(
                50L, 10L, VisitEntryType.HOME_UNLIMITED)).thenReturn(java.util.Optional.of(home));
        when(gymVisitRepository.countCountableBySubscriptionAndPeriodAndType(
                50L, LocalDate.of(2026, 10, 5), VisitEntryType.HOME_UNLIMITED)).thenReturn(7L);
        when(packageRepository.findAllById(any())).thenReturn(List.of(packageRow(3L, "Gold")));

        VisitHistoryResponse response = service.getHistory(USER, query(
                null, null, null, null, null, VisitHistoryMode.HOME_GYM, null, 20));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).mode()).isEqualTo("HOME_GYM");
        assertThat(response.subscriptionPeriod().mode()).isEqualTo("HOME_GYM");
        assertThat(response.subscriptionPeriod().homeGym().used()).isEqualTo(7);
        assertThat(response.subscriptionPeriod().homeGym().limit()).isEqualTo(30);
        assertThat(response.subscriptionPeriod().homeGym().name()).isEqualTo("Alpha");
    }

    // ------------------------------------------------------------------
    // Limits are clamped and defaults applied
    // ------------------------------------------------------------------

    @Test
    void limitIsClampedToFifty() {
        when(subscriptionResolver.resolve(USER)).thenReturn(null);

        service.getHistory(USER, query(null, null, null, null, null, null, null, 500));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(gymVisitRepository).findHistory(eq(USER), any(), any(), anyString(), anyBoolean(),
                any(), anyBoolean(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(51);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private VisitHistoryQuery query(VisitHistoryPreset preset, LocalDate from, LocalDate to,
                                    VisitHistoryStatus status, Long gymId, VisitHistoryMode mode,
                                    String cursor, int limit) {
        return new VisitHistoryQuery(preset, from, to, status, gymId, mode, cursor, limit);
    }

    private Subscription subscription(Long id, Long packageId, EntryMode mode, Integer totalLimit) {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(id);
        subscription.setUserId(USER);
        subscription.setPackageId(packageId);
        subscription.setStatus("ACTIVE");
        subscription.setStartAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        subscription.setEndAt(LocalDateTime.of(2026, 12, 5, 9, 0));
        subscription.setTotalLimit(totalLimit);
        subscription.setEntryMode(mode);
        return subscription;
    }

    private GymVisit visit(Long id, Long gymId, String gymName, LocalDateTime at,
                           VisitEntryType type, Subscription subscription) {
        GymVisit visit = GymVisit.builder()
                .userId(USER)
                .gymId(gymId)
                .gymName(gymName)
                .subscriptionId(subscription.getSubscriptionId())
                .checkedInAt(at)
                .entryType(type)
                .countsTowardsLimit(Boolean.TRUE)
                .build();
        visit.setId(id);
        visit.setPeriodStart(periodCalculator.periodStart(subscription.getStartAt(), at.toLocalDate()));
        return visit;
    }

    private VisitAttempt attempt(Long id, Long gymId, String gymName, LocalDateTime at,
                                 String rejectCode, String mode, Subscription subscription) {
        VisitAttempt attempt = VisitAttempt.builder()
                .userId(USER)
                .gymId(gymId)
                .gymName(gymName)
                .subscriptionId(subscription.getSubscriptionId())
                .mode(mode)
                .rejectCode(rejectCode)
                .scannedAt(at)
                .build();
        attempt.setId(id);
        return attempt;
    }

    private SubscriptionPackage packageRow(Long id, String name) {
        SubscriptionPackage row = new SubscriptionPackage();
        row.setId(id);
        row.setName(name);
        return row;
    }
}
