package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.model.entity.promo.PromoCampaign;
import az.fitnest.order.model.entity.promo.PromoGrant;
import az.fitnest.order.model.entity.promo.PromoGrantStatus;
import az.fitnest.order.pricingcampaign.dto.ActivePricingCampaignDto;
import az.fitnest.order.pricingcampaign.dto.PricingDto;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.entity.PricingCampaignStatus;
import az.fitnest.order.pricingcampaign.repository.PricingCampaignRedemptionRepository;
import az.fitnest.order.pricingcampaign.repository.PricingCampaignRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.SubscriptionRepository;
import az.fitnest.order.repository.promo.PromoCampaignRepository;
import az.fitnest.order.repository.promo.PromoGrantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingCampaignServiceTest {

    private static final long USER = 42L;

    @Mock PricingCampaignRepository campaignRepository;
    @Mock PricingCampaignRedemptionRepository redemptionRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock SubscriptionPackageRepository packageRepository;
    @Mock PromoCampaignRepository promoCampaignRepository;
    @Mock PromoGrantRepository promoGrantRepository;
    @InjectMocks PricingCampaignService service;

    private PricingCampaign campaign;

    @BeforeEach
    void setUp() {
        campaign = PricingMathTest.campaign(PricingCampaignStatus.ACTIVE);
        campaign.setId(UUID.randomUUID());
        campaign.setCode("bf_2026");
        campaign.setType(PricingCampaign.TYPE_BLACK_FRIDAY);
        campaign.setMaxPercent(30);
        campaign.setStartsAt(OffsetDateTime.now().minusDays(1));
        campaign.setEndsAt(OffsetDateTime.now().plusDays(1));
        campaign.getDiscounts().addAll(List.of(
                PricingMathTest.tier(3, "20"), PricingMathTest.tier(6, "25"), PricingMathTest.tier(12, "30")));
        lenient().when(campaignRepository.findByStatusAndType(PricingCampaignStatus.ACTIVE, PricingCampaign.TYPE_BLACK_FRIDAY))
                .thenReturn(List.of(campaign));
        lenient().when(promoCampaignRepository.findByCodeNormalized(any())).thenReturn(Optional.empty());
    }

    @Test
    void activeCampaignReportsUsedByUserAndHidesCountdownWhenSubscribed() {
        when(redemptionRepository.existsByCampaignIdAndUserId(campaign.getId(), USER)).thenReturn(true);
        when(subscriptionRepository.existsByUserIdAndStatusIn(eq(USER), eq(List.of("ACTIVE", "FROZEN")))).thenReturn(true);
        when(subscriptionRepository.existsByUserIdAndStatusIn(eq(USER), eq(List.of("PENDING")))).thenReturn(false);

        ActivePricingCampaignDto dto = service.findActiveForUser(USER).orElseThrow();

        assertTrue(dto.usedByUser());
        assertTrue(dto.eligible());
        assertFalse(dto.showCountdown());
        assertFalse(dto.nextPurchaseOffer().available(), "used campaign must not offer next purchase");
        assertEquals(3, dto.discounts().size());
    }

    @Test
    void freshUserWithoutSubscriptionSeesCountdownAndNoNextPurchaseOffer() {
        ActivePricingCampaignDto dto = service.findActiveForUser(USER).orElseThrow();

        assertFalse(dto.usedByUser());
        assertTrue(dto.showCountdown());
        assertFalse(dto.nextPurchaseOffer().available());
    }

    @Test
    void nextPurchaseOfferAvailableForSubscriberWithoutQueuedOrUsed() {
        when(subscriptionRepository.existsByUserIdAndStatusIn(eq(USER), eq(List.of("ACTIVE", "FROZEN")))).thenReturn(true);

        assertTrue(service.findActiveForUser(USER).orElseThrow().nextPurchaseOffer().available());
    }

    @Test
    void bakcell20GrantHolderIsNotEligibleAndGetsNoDiscount() {
        PromoCampaign promo = new PromoCampaign();
        promo.setId(UUID.randomUUID());
        PromoGrant grant = new PromoGrant();
        grant.setStatus(PromoGrantStatus.GRANTED);
        when(promoCampaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(promo));
        when(promoGrantRepository.findByCampaignIdAndUserId(promo.getId(), USER)).thenReturn(Optional.of(grant));

        assertFalse(service.findActiveForUser(USER).orElseThrow().eligible());
        assertTrue(service.applicableCampaign(USER).isEmpty());
        assertFalse(service.computePricing(new BigDecimal("100"), 12, USER).campaignApplied());
    }

    @Test
    void outsideWindowOrDisabledReturnsEmpty() {
        campaign.setEndsAt(OffsetDateTime.now().minusSeconds(1));
        assertTrue(service.findActiveForUser(USER).isEmpty());

        campaign.setEndsAt(OffsetDateTime.now().plusDays(1));
        campaign.setStatus(PricingCampaignStatus.DISABLED);
        assertTrue(service.findActiveForUser(USER).isEmpty());
    }

    @Test
    void computePricingAppliesTierFromStandardPriceOnly() {
        PricingDto p = service.computePricing(new BigDecimal("120.00"), 12, USER);

        assertTrue(p.campaignApplied());
        assertEquals(new BigDecimal("84.00"), p.finalPrice());
        assertEquals(new BigDecimal("36.00"), p.savedAmount());
        assertEquals(new BigDecimal("7.00"), p.monthlyPrice());
        assertEquals(new BigDecimal("10.00"), p.monthlyListPrice());
        assertEquals(campaign.getId(), p.campaignId());

        PricingDto monthOne = service.computePricing(new BigDecimal("20.00"), 1, USER);
        assertFalse(monthOne.campaignApplied());
        assertEquals(new BigDecimal("20.00"), monthOne.finalPrice());
    }

    @Test
    void usedUserPaysListPrice() {
        when(redemptionRepository.existsByCampaignIdAndUserId(campaign.getId(), USER)).thenReturn(true);

        PricingDto p = service.computePricing(new BigDecimal("120.00"), 12, USER);

        assertFalse(p.campaignApplied());
        assertEquals(new BigDecimal("120.00"), p.finalPrice());
    }

    @Test
    void markRedeemedDelegatesToIdempotentInsert() {
        service.markRedeemed(USER, campaign.getId(), "ord_1");
        verify(redemptionRepository).insertIgnoreDuplicate(campaign.getId(), USER, "ord_1");
    }
}
