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

    /** Reservation expiry job releases expired rows in bulk */
    @Test
    void expireReservations_freesExpiredRows() {
        when(grantRepository.releaseExpiredReservations(any())).thenReturn(1);

        grantService.expireReservations();

        verify(grantRepository).releaseExpiredReservations(any());
    }

    /** A declined payment must not lock the code: an expired reservation is taken over. */
    @Test
    void reserve_expiredReservation_isReclaimable() {
        PromoCampaign campaign = new PromoCampaign();
        campaign.setId(campaignId);
        campaign.setCode("Bakcell");
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        grant.setStatus(PromoGrantStatus.RESERVED);
        grant.setReservedOrderId("ord_old");
        grant.setReservedUntil(OffsetDateTime.now().minusMinutes(1));
        when(grantRepository.findByCampaignIdAndUserIdWithLock(campaignId, 10L)).thenReturn(Optional.of(grant));
        when(grantRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PromoGrant result = grantService.reserve(campaignId, 10L, "ord_new", OffsetDateTime.now().plusMinutes(10));

        assertEquals(PromoGrantStatus.RESERVED, result.getStatus());
        assertEquals("ord_new", result.getReservedOrderId());
        assertTrue(result.getReservedUntil().isAfter(OffsetDateTime.now()));
        verify(eventPublisher).grantReserved(campaignId, "Bakcell", null, 10L, "hash123", "ord_new");
    }

    /** A reservation without a window or holder can no longer be attributed to a live order. */
    @Test
    void reserve_reservationWithoutWindow_isReclaimable() {
        grant.setStatus(PromoGrantStatus.RESERVED);
        grant.setReservedOrderId("ord_old");
        grant.setReservedUntil(null);
        when(grantRepository.findByCampaignIdAndUserIdWithLock(campaignId, 10L)).thenReturn(Optional.of(grant));
        when(grantRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PromoGrant result = grantService.reserve(campaignId, 10L, "ord_new", OffsetDateTime.now().plusMinutes(10));

        assertEquals("ord_new", result.getReservedOrderId());
    }

    /** AC-11: a live reservation still blocks a parallel order. */
    @Test
    void reserve_liveReservationForAnotherOrder_throwsReserved() {
        grant.setStatus(PromoGrantStatus.RESERVED);
        grant.setReservedOrderId("ord_live");
        grant.setReservedUntil(OffsetDateTime.now().plusMinutes(5));
        when(grantRepository.findByCampaignIdAndUserIdWithLock(campaignId, 10L)).thenReturn(Optional.of(grant));

        PromoReservationException ex = assertThrows(PromoReservationException.class,
                () -> grantService.reserve(campaignId, 10L, "ord_new", OffsetDateTime.now().plusMinutes(10)));

        assertEquals("error.promo_reserved", ex.getErrorCode());
        verify(grantRepository, never()).save(any());
    }

    /** Retrying the same order refreshes its own reservation instead of failing. */
    @Test
    void reserve_sameOrder_isIdempotent() {
        grant.setStatus(PromoGrantStatus.RESERVED);
        grant.setReservedOrderId("ord_1");
        grant.setReservedUntil(OffsetDateTime.now().plusMinutes(5));
        when(grantRepository.findByCampaignIdAndUserIdWithLock(campaignId, 10L)).thenReturn(Optional.of(grant));
        when(grantRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PromoGrant result = grantService.reserve(campaignId, 10L, "ord_1", OffsetDateTime.now().plusMinutes(10));

        assertEquals(PromoGrantStatus.RESERVED, result.getStatus());
        assertEquals("ord_1", result.getReservedOrderId());
    }
}

/**
 * Shared usability gate (used by quote AND order paths alike).
 * Package-private top-level class in the same file; runs as its own test class.
 */
class AssertGrantUsableTest {

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
    private PromoCampaign campaign;
    private PromoGrant grant;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        campaignId = UUID.randomUUID();
        campaign = new PromoCampaign();
        campaign.setId(campaignId);
        campaign.setStatus(PromoCampaignStatus.ACTIVE);
        campaign.setStartsAt(OffsetDateTime.now().minusDays(1));
        campaign.setEndsAt(OffsetDateTime.now().plusDays(1));
        campaign.setPlanIds(new String[]{"12"});
        grant = new PromoGrant();
        grant.setId(UUID.randomUUID());
        grant.setCampaignId(campaignId);
        grant.setUserId(10L);
        grant.setStatus(PromoGrantStatus.GRANTED);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(grantRepository.findByCampaignIdAndUserId(campaignId, 10L)).thenReturn(Optional.of(grant));
    }

    @Test
    void happyPath_returnsGrant() {
        assertSame(grant, grantService.assertGrantUsable(campaignId, 10L, "12"));
    }

    @Test
    void paused_throwsNotActive() {
        campaign.setStatus(PromoCampaignStatus.PAUSED);
        assertEquals("error.promo_not_active", assertThrows(
                az.fitnest.order.exception.BadRequestException.class,
                () -> grantService.assertGrantUsable(campaignId, 10L, "12")).getMessage());
    }

    @Test
    void ended_throwsExpired() {
        campaign.setEndsAt(OffsetDateTime.now().minusSeconds(1));
        assertEquals("error.promo_expired", assertThrows(
                az.fitnest.order.exception.BadRequestException.class,
                () -> grantService.assertGrantUsable(campaignId, 10L, "12")).getMessage());
    }

    @Test
    void limitReached_throwsLimitReached() {
        campaign.setTotalLimit(5);
        campaign.setRedeemedCount(5);
        assertEquals("error.promo_limit_reached", assertThrows(
                az.fitnest.order.exception.BadRequestException.class,
                () -> grantService.assertGrantUsable(campaignId, 10L, "12")).getMessage());
    }

    @Test
    void wrongPlan_throwsPlanNotAllowed() {
        assertEquals("error.promo_plan_not_allowed", assertThrows(
                az.fitnest.order.exception.BadRequestException.class,
                () -> grantService.assertGrantUsable(campaignId, 10L, "99")).getMessage());
    }

    @Test
    void noGrant_throwsNotEligible() {
        when(grantRepository.findByCampaignIdAndUserId(campaignId, 10L)).thenReturn(Optional.empty());
        assertEquals("error.promo_not_eligible", assertThrows(
                az.fitnest.order.exception.BadRequestException.class,
                () -> grantService.assertGrantUsable(campaignId, 10L, "12")).getMessage());
    }

    @Test
    void redeemed_throwsAlreadyUsed() {
        grant.setStatus(PromoGrantStatus.REDEEMED);
        assertEquals("error.promo_already_used", assertThrows(
                az.fitnest.order.exception.BadRequestException.class,
                () -> grantService.assertGrantUsable(campaignId, 10L, "12")).getMessage());
    }

    @Test
    void revoked_throwsNotActive() {
        grant.setStatus(PromoGrantStatus.REVOKED);
        assertEquals("error.promo_not_active", assertThrows(
                az.fitnest.order.exception.BadRequestException.class,
                () -> grantService.assertGrantUsable(campaignId, 10L, "12")).getMessage());
    }
}
