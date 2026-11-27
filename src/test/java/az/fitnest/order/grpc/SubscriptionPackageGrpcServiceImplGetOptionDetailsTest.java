package az.fitnest.order.grpc;

import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.pricingcampaign.dto.PricingDto;
import az.fitnest.order.pricingcampaign.service.PricingCampaignService;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.service.homegym.HomeGymCheckInService;
import az.fitnest.order.service.impl.UserSubscriptionService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionPackageGrpcServiceImplGetOptionDetailsTest {

    @Mock SubscriptionPackageRepository packageRepository;
    @Mock UserSubscriptionService userSubscriptionService;
    @Mock HomeGymCheckInService homeGymCheckInService;
    @Mock PricingCampaignService pricingCampaignService;
    @Mock StreamObserver<GetOptionDetailsResponse> responseObserver;

    private SubscriptionPackageGrpcServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionPackageGrpcServiceImpl(
                packageRepository, userSubscriptionService, homeGymCheckInService, pricingCampaignService);
    }

    @Test
    void campaignAppliedIgnoresStalePriceDiscounted() {
        PackageOption option = option(9L, 12, "480.00", "336.00"); // stale ~30% sale
        stubPackage(1L, option);
        when(pricingCampaignService.computePricing(eq(new BigDecimal("480.00")), eq(12), eq(42L)))
                .thenReturn(pricing("480.00", "240.00", "50", true));

        service.getOptionDetails(request(1L, 9L, 42L), responseObserver);

        GetOptionDetailsResponse resp = captureResponse();
        assertEquals(240.0, resp.getAmount(), 0.001);
        assertEquals(12, resp.getDurationMonths());
        assertEquals("AZN", resp.getCurrency());
    }

    @Test
    void noCampaignFallsBackToLegacyPriceDiscounted() {
        PackageOption option = option(9L, 12, "480.00", "336.00");
        stubPackage(1L, option);
        when(pricingCampaignService.computePricing(eq(new BigDecimal("480.00")), eq(12), eq(7L)))
                .thenReturn(pricing("480.00", "480.00", "0", false));

        service.getOptionDetails(request(1L, 9L, 7L), responseObserver);

        assertEquals(336.0, captureResponse().getAmount(), 0.001);
    }

    @Test
    void noCampaignAndNoDiscountedUsesStandard() {
        PackageOption option = option(1L, 1, "45.00", null);
        stubPackage(1L, option);
        when(pricingCampaignService.computePricing(eq(new BigDecimal("45.00")), eq(1), isNull(Long.class)))
                .thenReturn(pricing("45.00", "45.00", "0", false));

        service.getOptionDetails(request(1L, 1L, 0L), responseObserver);

        assertEquals(45.0, captureResponse().getAmount(), 0.001);
        verify(pricingCampaignService).computePricing(eq(new BigDecimal("45.00")), eq(1), isNull(Long.class));
    }

    private GetOptionDetailsResponse captureResponse() {
        ArgumentCaptor<GetOptionDetailsResponse> captor = ArgumentCaptor.forClass(GetOptionDetailsResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        return captor.getValue();
    }

    private void stubPackage(long packageId, PackageOption option) {
        SubscriptionPackage pkg = new SubscriptionPackage();
        pkg.setId(packageId);
        pkg.setCurrency("AZN");
        pkg.setOptions(Set.of(option));
        when(packageRepository.findFullById(packageId)).thenReturn(Optional.of(pkg));
    }

    private static GetOptionDetailsRequest request(long packageId, long optionId, long userId) {
        return GetOptionDetailsRequest.newBuilder()
                .setPackageId(packageId)
                .setOptionId(optionId)
                .setUserId(userId)
                .build();
    }

    private static PackageOption option(long id, int months, String standard, String discounted) {
        PackageOption o = new PackageOption();
        o.setId(id);
        o.setDurationMonths(months);
        o.setPriceStandard(new BigDecimal(standard));
        o.setPriceDiscounted(discounted == null ? null : new BigDecimal(discounted));
        return o;
    }

    private static PricingDto pricing(String list, String fin, String pct, boolean applied) {
        return new PricingDto(
                new BigDecimal(list),
                new BigDecimal(fin),
                new BigDecimal(list).subtract(new BigDecimal(fin)),
                new BigDecimal(pct),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                applied,
                applied ? UUID.randomUUID() : null);
    }
}
