package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.pricingcampaign.dto.CampaignLandingDto;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignLandingI18n;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignStatus;
import az.fitnest.order.pricingcampaign.repository.PricingCampaignLandingI18nRepository;
import az.fitnest.order.pricingcampaign.repository.PricingCampaignRedemptionRepository;
import az.fitnest.order.pricingcampaign.repository.PricingCampaignRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import az.fitnest.order.repository.promo.PromoGrantRepository;
import az.fitnest.order.service.TranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingCampaignLandingServiceTest {

    @Mock PricingCampaignRepository campaignRepository;
    @Mock PricingCampaignRedemptionRepository redemptionRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock SubscriptionPackageRepository packageRepository;
    @Mock PromoCampaignRepository promoCampaignRepository;
    @Mock PromoGrantRepository promoGrantRepository;
    @Mock PricingCampaignLandingI18nRepository landingRepository;
    @Mock TranslationService translationService;

    private PricingCampaign campaign;
    private PricingCampaignLandingService service;

    @BeforeEach
    void setUp() {
        // Real PricingCampaignService on mocked repos: the point is that landing prices match the BF math.
        PricingCampaignService campaignService = new PricingCampaignService(campaignRepository,
                redemptionRepository, subscriptionRepository, packageRepository, promoCampaignRepository,
                promoGrantRepository);
        service = new PricingCampaignLandingService(campaignService, landingRepository, packageRepository,
                translationService, new ObjectMapper());

        campaign = PricingMathTest.campaign(PricingCampaignStatus.ACTIVE);
        campaign.setId(UUID.randomUUID());
        campaign.setCode("bf_2026");
        campaign.setType(PricingCampaign.TYPE_BLACK_FRIDAY);
        campaign.setMaxPercent(30);
        // PricingMath.isActiveAt uses now(), so the window is relative rather than the fixed seed dates.
        campaign.setStartsAt(OffsetDateTime.now().minusDays(1));
        campaign.setEndsAt(OffsetDateTime.now().plusDays(1));
        campaign.getDiscounts().addAll(List.of(
                PricingMathTest.tier(3, "20.00"), PricingMathTest.tier(6, "25.00"), PricingMathTest.tier(12, "30.00")));
        lenient().when(campaignRepository.findByStatusAndType(PricingCampaignStatus.ACTIVE,
                PricingCampaign.TYPE_BLACK_FRIDAY)).thenReturn(List.of(campaign));
        lenient().when(promoCampaignRepository.findByCodeNormalized(any())).thenReturn(Optional.empty());
        lenient().when(translationService.getTranslatedValue(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);
    }

    @Test
    void activeCampaignReturnsOptionsForAllPackagesAndLocalizedHeader() {
        when(packageRepository.findByIsActiveTrueOrdered()).thenReturn(List.of(
                pkg(1L, "Bronze", opt(11L, 1, "50"), opt(12L, 3, "150"), opt(13L, 12, "600")),
                pkg(2L, "Gold", opt(21L, 3, "300"), opt(22L, 6, "500"), opt(23L, 12, "1000"))));
        when(landingRepository.findByCampaignIdAndLocale(campaign.getId(), "az")).thenReturn(Optional.of(azCopy()));

        CampaignLandingDto dto = service.findActiveLanding(null, "AZ").orElseThrow();

        assertEquals("az", dto.locale());
        assertEquals("Qara Cümə", dto.header().title());
        assertEquals("30%-dək endirim!", dto.header().headline());
        assertEquals("Planını seç", dto.cta().title());
        assertTrue(dto.eligible());
        assertFalse(dto.usedByUser());

        assertEquals(List.of(3, 6, 12), dto.options().stream().map(o -> o.months()).toList());
        CampaignLandingDto.Option three = dto.options().get(0);
        assertEquals("3 aylıq al - 20 % endirim", three.label());
        assertEquals(new BigDecimal("20"), three.percent());
        // 1-month options never appear; 3-month row lists BOTH packages that sell it.
        assertEquals(List.of(1L, 2L), three.packages().stream().map(p -> p.packageId()).toList());
        CampaignLandingDto.PackagePrice bronze3 = three.packages().get(0);
        assertEquals(12L, bronze3.optionId());
        assertEquals(new BigDecimal("150.00"), bronze3.listPrice());
        assertEquals(new BigDecimal("120.00"), bronze3.finalPrice());
        assertEquals(new BigDecimal("20.00"), bronze3.discountPercent());
        assertTrue(bronze3.campaignApplied());
        // 6-month row only has Gold.
        assertEquals(List.of(2L), dto.options().get(1).packages().stream().map(p -> p.packageId()).toList());
        assertEquals(5, dto.terms().size());
        assertTrue(dto.terms().get(0).contains(campaign.getStartsAt().format(
                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))), dto.terms().get(0));
        assertFalse(dto.terms().stream().anyMatch(t -> t.contains("{")), "placeholders must be substituted");
    }

    @Test
    void missingLocaleFallsBackToAzCopy() {
        when(packageRepository.findByIsActiveTrueOrdered()).thenReturn(List.of(pkg(1L, "Gold", opt(21L, 3, "300"))));
        when(landingRepository.findByCampaignIdAndLocale(campaign.getId(), "ru")).thenReturn(Optional.empty());
        when(landingRepository.findByCampaignIdAndLocale(campaign.getId(), "az")).thenReturn(Optional.of(azCopy()));

        CampaignLandingDto dto = service.findActiveLanding(null, "RU").orElseThrow();

        assertEquals("az", dto.locale());
        assertEquals("Qara Cümə", dto.header().title());
    }

    @Test
    void inactiveCampaignReturnsEmpty() {
        when(campaignRepository.findByStatusAndType(PricingCampaignStatus.ACTIVE, PricingCampaign.TYPE_BLACK_FRIDAY))
                .thenReturn(List.of());

        assertTrue(service.findActiveLanding(42L, "AZ").isEmpty());
    }

    @Test
    void usedUserStillGetsPayloadWithFlag() {
        when(packageRepository.findByIsActiveTrueOrdered()).thenReturn(List.of(pkg(1L, "Gold", opt(21L, 3, "300"))));
        when(landingRepository.findByCampaignIdAndLocale(eq(campaign.getId()), anyString()))
                .thenReturn(Optional.of(azCopy()));
        when(redemptionRepository.existsByCampaignIdAndUserId(campaign.getId(), 42L)).thenReturn(true);

        CampaignLandingDto dto = service.findActiveLanding(42L, "AZ").orElseThrow();

        assertTrue(dto.usedByUser());
    }

    private PricingCampaignLandingI18n azCopy() {
        PricingCampaignLandingI18n i = new PricingCampaignLandingI18n();
        i.setCampaignId(campaign.getId());
        i.setLocale("az");
        i.setTitle("Qara Cümə");
        i.setHeadline("{maxPercent}%-dək endirim!");
        i.setSubtitle("3, 6 və 12 aylıq abunəlikləri Qara Cümə qiyməti ilə al.");
        i.setOptionLabelTemplate("{months} aylıq al - {percent} % endirim");
        i.setCtaTitle("Planını seç");
        i.setTermsJson("[\"Kampaniya {startsAt} - {endsAt} tarixləri arasında keçərlidir.\",\"b\",\"c\",\"d\","
                + "\"e {endsAtTime}\"]");
        return i;
    }

    private static SubscriptionPackage pkg(Long id, String name, PackageOption... options) {
        SubscriptionPackage p = new SubscriptionPackage();
        p.setId(id);
        p.setName(name);
        p.setCurrency("AZN");
        Set<PackageOption> set = new HashSet<>();
        for (PackageOption o : options) {
            o.setSubscriptionPackage(p);
            set.add(o);
        }
        p.setOptions(set);
        return p;
    }

    private static PackageOption opt(Long id, int months, String price) {
        PackageOption o = new PackageOption();
        o.setId(id);
        o.setDurationMonths(months);
        o.setPriceStandard(new BigDecimal(price));
        o.setIsActive(true);
        return o;
    }
}
