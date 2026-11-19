package az.fitnest.order.service.impl;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.dto.BonusDecision;
import az.fitnest.order.dto.PackageOptionDto;
import az.fitnest.order.dto.SubscriptionPackageDto;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignDiscount;
import az.fitnest.order.pricingcampaign.service.PricingCampaignService;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.service.CampaignEligibilityService;
import az.fitnest.order.service.TranslationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * v1 /subscription-packages must show the Black Friday price (same rule as v4/checkout), because the
 * mobile gym detail loads v1 and only v4 knew about the campaign.
 */
class PackageCatalogServiceCampaignTest {

    private static final Long USER_ID = 42L;

    private SubscriptionPackageRepository packageRepository;
    private PricingCampaignService pricingCampaignService;
    private CampaignEligibilityService eligibility;
    private PackageCatalogService service;

    private SubscriptionPackage pkg;
    private PricingCampaign campaign;

    @BeforeEach
    void setUp() {
        packageRepository = mock(SubscriptionPackageRepository.class);
        pricingCampaignService = mock(PricingCampaignService.class);
        eligibility = mock(CampaignEligibilityService.class);
        TranslationService translation = mock(TranslationService.class);
        service = new PackageCatalogService(
                packageRepository, translation, mock(CatalogServiceGrpcClient.class), eligibility,
                pricingCampaignService);

        pkg = new SubscriptionPackage();
        pkg.setId(7L);
        pkg.setName("Gold");
        pkg.setCurrency("AZN");
        pkg.setIsActive(true);
        pkg.getOptions().add(option(70L, 1, "100.00", null));
        // Legacy priceDiscounted (90) must be ignored while Black Friday applies.
        pkg.getOptions().add(option(71L, 3, "300.00", "270.00"));
        when(packageRepository.findById(7L)).thenReturn(Optional.of(pkg));
        when(packageRepository.findAllOrdered()).thenReturn(List.of(pkg));

        when(eligibility.resolve(any(), anyLong(), any(), any(), any()))
                .thenReturn(BonusDecision.notApplied(1));

        campaign = new PricingCampaign();
        campaign.setId(UUID.randomUUID());
        PricingCampaignDiscount tier = new PricingCampaignDiscount();
        tier.setMonths(3);
        tier.setPercent(new BigDecimal("40"));
        campaign.getDiscounts().add(tier);

        // computePricing(List, months, campaign) does not touch repositories, so a bare instance gives real math.
        PricingCampaignService realMath = new PricingCampaignService(null, null, null, null, null, null);
        when(pricingCampaignService.computePricing(any(BigDecimal.class), anyInt(), any(PricingCampaign.class)))
                .thenAnswer(inv -> realMath.computePricing(inv.getArgument(0), inv.<Integer>getArgument(1),
                        inv.<PricingCampaign>getArgument(2)));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static PackageOption option(Long id, int months, String standard, String discounted) {
        PackageOption o = new PackageOption();
        o.setId(id);
        o.setDurationMonths(months);
        o.setPriceStandard(new BigDecimal(standard));
        o.setPriceDiscounted(discounted == null ? null : new BigDecimal(discounted));
        o.setIsActive(true);
        return o;
    }

    private static void loginAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private PackageOptionDto optionDto(long optionId) {
        return service.getOptionsByPlanId(7L).stream()
                .filter(o -> o.optionId().equals(optionId)).findFirst().orElseThrow();
    }

    @Test
    void applicableCampaignMakesEffectiveTheCampaignFinalPriceAndBaseTheListPrice() {
        loginAs(USER_ID);
        when(pricingCampaignService.applicableCampaign(USER_ID)).thenReturn(Optional.of(campaign));

        PackageOptionDto dto = optionDto(71L);

        assertEquals(0, new BigDecimal("300.00").compareTo(dto.price().base()));
        assertEquals(0, new BigDecimal("180.00").compareTo(dto.price().effective()));
        assertEquals(0, new BigDecimal("180.00").compareTo(dto.price().discount()));
        assertEquals(0, new BigDecimal("300.00").compareTo(dto.strikeThroughTotal()));
        assertEquals(0, new BigDecimal("120.00").compareTo(dto.savingsAmount()));
        assertEquals(0, new BigDecimal("60.00").compareTo(dto.monthlyEquivalent()));
        assertEquals("discount", dto.badge());
    }

    @Test
    void optionWithoutTierKeepsLegacyPricingEvenWhenCampaignApplies() {
        loginAs(USER_ID);
        when(pricingCampaignService.applicableCampaign(USER_ID)).thenReturn(Optional.of(campaign));

        // 1-month has no BF tier => campaignApplied=false => legacy (no priceDiscounted here => list price).
        PackageOptionDto dto = optionDto(70L);

        assertEquals(0, new BigDecimal("100.00").compareTo(dto.price().effective()));
        assertNull(dto.badge());
        assertNull(dto.strikeThroughTotal());
    }

    @Test
    void withoutCampaignLegacyPriceDiscountedStillWorks() {
        loginAs(USER_ID);
        when(pricingCampaignService.applicableCampaign(USER_ID)).thenReturn(Optional.empty());

        PackageOptionDto dto = optionDto(71L);

        assertEquals(0, new BigDecimal("300.00").compareTo(dto.price().base()));
        assertEquals(0, new BigDecimal("270.00").compareTo(dto.price().effective()));
        assertEquals(0, new BigDecimal("270.00").compareTo(dto.price().discount()));
        assertEquals(0, new BigDecimal("300.00").compareTo(dto.strikeThroughTotal()));
        assertEquals(0, new BigDecimal("30.00").compareTo(dto.savingsAmount()));
        assertEquals(0, new BigDecimal("90.00").compareTo(dto.monthlyEquivalent()));
        assertEquals("discount", dto.badge());
    }

    @Test
    void anonymousCallerNeverGetsCampaignPricesAndCampaignIsNotQueried() {
        PackageOptionDto dto = optionDto(71L);

        assertEquals(0, new BigDecimal("270.00").compareTo(dto.price().effective()));
        verifyNoInteractions(pricingCampaignService);
    }

    @Test
    void campaignIsResolvedOncePerCatalogCall() {
        loginAs(USER_ID);
        when(pricingCampaignService.applicableCampaign(USER_ID)).thenReturn(Optional.of(campaign));

        service.getOptionsByPlanId(7L);

        verify(pricingCampaignService, times(1)).applicableCampaign(USER_ID);
    }

    @Test
    void optionDetailsDtoAlsoCarriesCampaignPrice() {
        loginAs(USER_ID);
        when(pricingCampaignService.applicableCampaign(USER_ID)).thenReturn(Optional.of(campaign));

        SubscriptionPackageDto dto = service.getOptionDetails(7L, 71L);

        assertEquals(0, new BigDecimal("300.00").compareTo(dto.price().base()));
        assertEquals(0, new BigDecimal("180.00").compareTo(dto.price().effective()));
        assertEquals("discount", dto.badge());
    }
}
