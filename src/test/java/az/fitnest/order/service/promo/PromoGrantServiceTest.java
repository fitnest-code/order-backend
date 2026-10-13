package az.fitnest.order.service.promo;

import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.promo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for grant lifecycle: GRANTED -> RESERVED -> REDEEMED and BR-8 (one
 * account per number) / AC-11 (only one parallel order succeeds).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromoGrantServiceTest {

    @Mock
    private PromoGrantRepository grantRepository;
    @Mock
    private PromoCampaignRepository campaignRepository;
    @Mock
    private PromoPhoneHasher phoneHasher;
    @Mock
    private PromoAudienceNumberRepository audienceNumberRepository;
    @Mock
    private PromoEventPublisher eventPublisher;

    @InjectMocks
    private PromoGrantService grantService;

    private UUID campaignId;
    private PromoGrant grant;

    @BeforeEach
    void setUp() {
        campaignId = UUID.randomUUID();
        grant = new PromoGrant();
        grant.setId(UUID.randomUUID());
        grant.setCampaignId(campaignId);
        grant.setUserId(10L);
        grant.setPhoneHash("hash123");
        grant.setStatus(PromoGrantStatus.GRANTED);
        grant.setPopupStatus(PromoPopupStatus.PENDING);
        grant.setVersion(0);
    }

    /** BR-8: no new grant when the number is already REDEEMED elsewhere */
    @Test
    void createGrant_alreadyRedeemed_returnsNull() {
        when(grantRepository.findByCampaignIdAndPhoneHashAndStatus(campaignId, "hash123", PromoGrantStatus.REDEEMED))
                .thenReturn(Optional.of(new PromoGrant()));

        PromoGrant result = grantService.createGrant(campaignId, 11L, "hash123");
        assertNull(result);
        verify(grantRepository, never()).save(any());
    }

    /** BR-8: no duplicate grant for same user on same campaign */
    @Test
    void createGrant_userAlreadyHasGrant_returnsNull() {
        when(grantRepository.findByCampaignIdAndPhoneHashAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(grantRepository.findByCampaignIdAndUserId(campaignId, 10L)).thenReturn(Optional.of(new PromoGrant()));

        PromoGrant result = grantService.createGrant(campaignId, 10L, "hash123");
        assertNull(result);
        verify(grantRepository, never()).save(any());
    }

    @Test
    void createGrant_newUser_createsGrant() {
        when(grantRepository.findByCampaignIdAndPhoneHashAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(grantRepository.findByCampaignIdAndUserId(campaignId, 10L)).thenReturn(Optional.empty());
        when(grantRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(campaignRepository.findById(any())).thenReturn(Optional.empty());

        PromoGrant result = grantService.createGrant(campaignId, 10L, "hash123");
        assertNotNull(result);
        assertEquals(PromoGrantStatus.GRANTED, result.getStatus());
        assertEquals(PromoPopupStatus.PENDING, result.getPopupStatus());
    }

    /** AC-3: popup seen marks SEEN and emits promo.popup.seen */
    @Test
    void markPopupSeen_setsSeen() {
        when(grantRepository.findById(grant.getId())).thenReturn(Optional.of(grant));

        grantService.markPopupSeen(grant.getId());

        assertEquals(PromoPopupStatus.SEEN, grant.getPopupStatus());
        assertNotNull(grant.getPopupSeenAt());
        verify(grantRepository).save(grant);
    }

    /** AC-8: reserved grant goes back to GRANTED when payment fails */
    @Test
    void release_reservedGrant_returnsToGranted() {
        grant.setStatus(PromoGrantStatus.RESERVED);
        grant.setReservedOrderId("ord_1");
        grant.setReservedUntil(OffsetDateTime.now().plusMinutes(30));
        when(grantRepository.findById(grant.getId())).thenReturn(Optional.of(grant));

        grantService.release(grant.getId());

        assertEquals(PromoGrantStatus.GRANTED, grant.getStatus());
        assertNull(grant.getReservedOrderId());
        assertNull(grant.getReservedUntil());
    }

    /** AC-9: redeem sets REDEEMED, snapshots amounts and increments campaign counter */
    @Test
    void redeem_setsRedeemedAndIncrementsCounter() {
        grant.setStatus(PromoGrantStatus.RESERVED);
        grant.setReservedOrderId("ord_1");
        when(grantRepository.findByIdWithLock(grant.getId())).thenReturn(Optional.of(grant));

        PromoCampaign campaign = new PromoCampaign();
        campaign.setId(campaignId);
        campaign.setCode("Bakcell20");
        campaign.setRedeemedCount(4);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));

        grantService.redeem(grant.getId(), "ord_1", "pay_1",
                BigDecimal.valueOf(50), BigDecimal.valueOf(10), BigDecimal.valueOf(40));

        assertEquals(PromoGrantStatus.REDEEMED, grant.getStatus());
        assertEquals("ord_1", grant.getRedeemedOrderId());
        assertEquals("pay_1", grant.getPaymentId());
        assertEquals(0, grant.getDiscountAmount().compareTo(new BigDecimal("10.00")));
        assertEquals(0, grant.getFinalAmount().compareTo(new BigDecimal("40.00")));
        assertNotNull(grant.getRedeemedAt());
        verify(campaignRepository).incrementRedeemedCount(campaignId);
    }

    /** Idempotency: redeeming twice does not double count */
    @Test
    void redeem_twice_isIdempotent() {
        grant.setStatus(PromoGrantStatus.REDEEMED);
        grant.setRedeemedOrderId("ord_1");
        when(grantRepository.findByIdWithLock(grant.getId())).thenReturn(Optional.of(grant));

        grantService.redeem(grant.getId(), "ord_1", "pay_1",
                BigDecimal.valueOf(50), BigDecimal.valueOf(10), BigDecimal.valueOf(40));

        verify(grantRepository, never()).save(grant);
        verify(campaignRepository, never()).save(any());
    }

    /** Reservation expiry job returns reserved grants to GRANTED */
    @Test
    void expireReservations_freesExpiredRows() {
        grant.setStatus(PromoGrantStatus.RESERVED);
        grant.setReservedUntil(OffsetDateTime.now().minusMinutes(5));
        when(grantRepository.findByStatusAndReservedUntilBefore(eq(PromoGrantStatus.RESERVED), any()))
                .thenReturn(List.of(grant));

        grantService.expireReservations();

        assertEquals(PromoGrantStatus.GRANTED, grant.getStatus());
        verify(grantRepository).save(grant);
    }
}
