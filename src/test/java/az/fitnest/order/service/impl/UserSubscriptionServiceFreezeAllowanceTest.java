package az.fitnest.order.service.impl;

import az.fitnest.order.dto.AdminAssignSubscriptionRequest;
import az.fitnest.order.dto.AdminAssignSubscriptionResponse;
import az.fitnest.order.dto.AdminUserSubscriptionResponse;
import az.fitnest.order.dto.freeze.FreezeEligibilityResponse;
import az.fitnest.order.event.SubscriptionEventPublisher;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.exception.ConflictException;
import az.fitnest.order.grpc.NotificationGrpcClient;
import az.fitnest.order.grpc.PaymentGrpcClient;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.Subscription;
import az.fitnest.order.model.entity.SubscriptionFreezeEntitlement;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.model.enums.FreezeStatus;
import az.fitnest.order.repository.CampaignOfferRepository;
import az.fitnest.order.repository.CampaignRepository;
import az.fitnest.order.repository.GymVisitRepository;
import az.fitnest.order.repository.OrderRepository;
import az.fitnest.order.repository.SubscriptionFreezeRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import az.fitnest.order.repository.UserCampaignRedemptionRepository;
import az.fitnest.order.service.CampaignEligibilityService;
import az.fitnest.order.service.TranslationService;
import az.fitnest.order.service.freeze.FreezeEntitlementService;
import az.fitnest.order.service.freeze.FreezeFinalizeService;
import az.fitnest.order.service.freeze.FreezeTierPolicyProvider;
import az.fitnest.order.service.freeze.SubscriptionFreezeService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserSubscriptionServiceFreezeAllowanceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionPackageRepository packageRepository;
    @Mock private GymVisitRepository gymVisitRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private SubscriptionEventPublisher subscriptionEventPublisher;
    @Mock private TranslationService translationService;
    @Mock private PaymentGrpcClient paymentGrpcClient;
    @Mock private NotificationGrpcClient notificationGrpcClient;
    @Mock private EntityManager entityManager;
    @Mock private FreezeEntitlementService freezeEntitlementService;
    @Spy private FreezeTierPolicyProvider freezeTierPolicyProvider = new FreezeTierPolicyProvider();
    @Mock private SubscriptionFreezeService subscriptionFreezeService;
    @Mock private SubscriptionFreezeRepository subscriptionFreezeRepository;
    @Mock private FreezeFinalizeService freezeFinalizeService;
    @Mock private CampaignEligibilityService campaignEligibilityService;
    @Mock private UserCampaignRedemptionRepository userCampaignRedemptionRepository;
    @Mock private CampaignOfferRepository campaignOfferRepository;
    @Mock private CampaignRepository campaignRepository;

    @InjectMocks private UserSubscriptionService service;

    @Test
    void monthlyJobResetsMultiMonthAllowanceAndSkipsSingleMonth() {
        Subscription gold = subscription(101L, 201L, 301L, "ACTIVE");
        Subscription bronze = subscription(102L, 202L, 302L, "ACTIVE");
        Subscription frozenGold = subscription(103L, 203L, 303L, "FROZEN");
        when(subscriptionRepository.findByStatusIn(List.of("ACTIVE", "FROZEN")))
                .thenReturn(List.of(gold, bronze, frozenGold));

        when(packageRepository.findById(201L)).thenReturn(Optional.of(pkg(201L, "Gold", 301L, 3)));
        when(packageRepository.findById(202L)).thenReturn(Optional.of(pkg(202L, "Bronze", 302L, 1)));
        when(packageRepository.findById(203L)).thenReturn(Optional.of(pkg(203L, "Gold", 303L, 6)));

        String month = YearMonth.now().toString();

        service.monthlyFreezeAllowanceReset();

        verify(freezeEntitlementService).resetMonthlyAllowance(101L, 10, month);
        verify(freezeEntitlementService).resetMonthlyAllowance(103L, 10, month);
        verify(freezeEntitlementService, never()).resetMonthlyAllowance(102L, 5, month);
    }

    @Test
    void adminFreezeAppliesTierAndRemainingBalanceValidation() {
        Subscription sub = subscription(101L, 201L, 301L, "ACTIVE");
        when(subscriptionRepository.findByUserIdAndStatus(500L, "ACTIVE")).thenReturn(List.of(sub));
        when(subscriptionFreezeService.getEligibility(500L, 101L)).thenReturn(
                FreezeEligibilityResponse.ok(10, 3, 0, 7, sub.getStartAt(), sub.getEndAt()));

        assertEquals("error.freeze.invalid_days", assertThrows(BadRequestException.class,
                () -> service.freezeUserSubscription(500L, 0)).getMessage());
        assertEquals("error.freeze.invalid_days", assertThrows(BadRequestException.class,
                () -> service.freezeUserSubscription(500L, 11)).getMessage());
        assertEquals("error.freeze.limit_exceeded", assertThrows(ConflictException.class,
                () -> service.freezeUserSubscription(500L, 8)).getMessage());

        verify(subscriptionFreezeService, never()).previewFreeze(any(), any(), anyInt());
    }

    @Test
    void adminAssignResponseUsesEntitlementAllowanceAndKeepsLegacyColumnAtZero() {
        AdminAssignSubscriptionRequest request = AdminAssignSubscriptionRequest.builder()
                .userId(500L)
                .planId(5L)
                .optionId(7L)
                .applyCampaign(false)
                .build();
        when(campaignEligibilityService.getBakuZone()).thenReturn(ZoneId.of("Asia/Baku"));
        when(packageRepository.findById(5L)).thenReturn(Optional.of(pkg(5L, "Gold", 7L, 3)));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> {
            Subscription saved = invocation.getArgument(0);
            saved.setSubscriptionId(900L);
            return saved;
        });
        when(freezeEntitlementService.createForSubscription(any(), eq("Gold"), eq("v1.2")))
                .thenReturn(entitlement(10, 0, 0));

        AdminAssignSubscriptionResponse response = service.assignSubscriptionToUser(request);

        assertEquals(10, response.allowedFreezeDays());
        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertEquals(0, captor.getValue().getAllowedFreezeDays());
    }

    @Test
    void adminDetailCapsLegacyCumulativeBudgetAtTierLimit() {
        Subscription sub = subscription(101L, 201L, 301L, "ACTIVE");
        when(subscriptionRepository.findAllByUserIdOrderByStartAtDesc(500L)).thenReturn(List.of(sub));
        when(packageRepository.findById(201L)).thenReturn(Optional.of(pkg(201L, "Gold", 301L, 3)));
        when(freezeEntitlementService.getBySubscriptionId(101L))
                .thenReturn(Optional.of(entitlement(30, 5, 0)));
        when(subscriptionFreezeRepository.findBySubscriptionIdAndStatus(101L, FreezeStatus.ACTIVE))
                .thenReturn(Optional.empty());

        AdminUserSubscriptionResponse response = service.getUserSubscriptionDetail(500L);

        assertEquals(10, response.totalFreezeDays());
        assertEquals(10, response.availableFreezeDays());
        assertTrue(response.canFreeze());
    }

    @Test
    void mobileSubscriptionDetailsCapLegacyCumulativeBudgetAtTierLimit() {
        Subscription sub = subscription(101L, 201L, 301L, "ACTIVE");
        when(subscriptionRepository.findAllByUserIdOrderByStartAtDesc(500L)).thenReturn(List.of(sub));
        when(packageRepository.findFullById(201L)).thenReturn(Optional.of(pkg(201L, "Platinum", 301L, 3)));
        when(freezeEntitlementService.getBySubscriptionId(101L))
                .thenReturn(Optional.of(entitlement(30, 4, 1)));

        var response = service.getActiveSubscription(500L);

        assertEquals(12, response.subscription().getAllowedFreezeDays());
        assertEquals(12, response.subscription().getAvailableFreezeDays());
        assertTrue(response.subscription().getCanFreeze());
    }

    private SubscriptionFreezeEntitlement entitlement(int totalDays, int consumedDays, int reservedDays) {
        return SubscriptionFreezeEntitlement.builder()
                .subscriptionId(101L)
                .userId(500L)
                .totalDays(totalDays)
                .consumedDays(consumedDays)
                .reservedDays(reservedDays)
                .policyVersion("v1.2")
                .allowanceMonth(YearMonth.now().toString())
                .build();
    }

    private Subscription subscription(Long subscriptionId, Long packageId, Long optionId, String status) {
        Subscription sub = new Subscription();
        sub.setSubscriptionId(subscriptionId);
        sub.setUserId(500L);
        sub.setPackageId(packageId);
        sub.setOptionId(optionId);
        sub.setStatus(status);
        sub.setStartAt(LocalDateTime.now().minusDays(1));
        sub.setEndAt(LocalDateTime.now().plusDays(90));
        return sub;
    }

    private SubscriptionPackage pkg(Long id, String name, Long optionId, int durationMonths) {
        SubscriptionPackage pkg = new SubscriptionPackage();
        pkg.setId(id);
        pkg.setName(name);
        pkg.setIsActive(true);

        PackageOption option = new PackageOption();
        option.setId(optionId);
        option.setDurationMonths(durationMonths);
        option.setSubscriptionPackage(pkg);
        pkg.setOptions(Set.of(option));
        return pkg;
    }
}
