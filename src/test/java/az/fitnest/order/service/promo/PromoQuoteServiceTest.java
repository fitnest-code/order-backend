package az.fitnest.order.service.promo;

import az.fitnest.order.dto.promo.PromoQuoteResponse;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.grpc.PaymentGrpcClient;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.model.entity.promo.*;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.repository.promo.*;
import az.fitnest.order.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PromoQuoteService (B12 server-side pricing, B13 identifiers).
 * Covers AC-4 (20% discount -> 40.00), AC-6 (not eligible -> PROMO_NOT_ELIGIBLE),
 * BR-2 (percent / fixed), max discount cap, FitCoin (B10), installments (B9).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromoQuoteServiceTest {

    @Mock
    private PromoGrantRepository grantRepository;
    @Mock
    private PromoCampaignRepository campaignRepository;
    @Mock
    private PromoCampaignTranslationRepository translationRepository;
    @Mock
    private PromoEligibilityService eligibilityService;
    @Mock
    private PromoRateLimitService rateLimitService;
    @Mock
    private SubscriptionPackageRepository packageRepository;
    @Mock
    private PaymentGrpcClient paymentGrpcClient;

    @InjectMocks
    private PromoQuoteService quoteService;

    private PromoCampaign percentCampaign;
    private PromoGrant granted;
    private MockedStatic<UserContext> userContextMock;

    private static final Long USER_ID = 100L;
    private static final String PACKAGE_ID = "12";
    private static final Long OPTION_ID = 34L;

    @BeforeEach
    void setUp() {
        userContextMock = mockStatic(UserContext.class);
        userContextMock.when(UserContext::getCurrentUserId).thenReturn(USER_ID);
        when(rateLimitService.tryConsumeQuote(anyLong())).thenReturn(true);
        when(paymentGrpcClient.getCoinWallet(anyLong()))
                .thenReturn(new PaymentGrpcClient.CoinWalletSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, null));

        SubscriptionPackage pkg = mock(SubscriptionPackage.class);
        PackageOption option = mock(PackageOption.class);
        when(option.getId()).thenReturn(OPTION_ID);
        when(option.getPriceDiscounted()).thenReturn(null);
        when(option.getPriceStandard()).thenReturn(BigDecimal.valueOf(50));
        when(pkg.getIsActive()).thenReturn(true);
        when(pkg.getCurrency()).thenReturn("AZN");
        when(pkg.getOptions()).thenReturn(Set.of(option));
        when(packageRepository.findById(12L)).thenReturn(Optional.of(pkg));

        percentCampaign = new PromoCampaign();
        percentCampaign.setId(UUID.randomUUID());
        percentCampaign.setCode("Bakcell20");
        percentCampaign.setCodeNormalized("BAKCELL20");
        percentCampaign.setDiscountType(PromoDiscountType.PERCENT);
        percentCampaign.setDiscountValue(BigDecimal.valueOf(20));
        percentCampaign.setStatus(PromoCampaignStatus.ACTIVE);
        percentCampaign.setStartsAt(OffsetDateTime.now().minusDays(10));
        percentCampaign.setEndsAt(OffsetDateTime.now().plusDays(30));
        percentCampaign.setPlanIds(new String[]{PACKAGE_ID});
        percentCampaign.setAllowFitcoin(true);
        percentCampaign.setAllowInstallments(true);
        percentCampaign.setUsesPerUser(1);
        percentCampaign.setPriority(0);

        granted = new PromoGrant();
        granted.setId(UUID.randomUUID());
        granted.setCampaignId(percentCampaign.getId());
        granted.setUserId(USER_ID);
        granted.setStatus(PromoGrantStatus.GRANTED);
    }

    @AfterEach
    void tearDown() {
        userContextMock.close();
    }

    private PromoQuoteResponse quote(String promoCode) {
        return quoteService.quote(PACKAGE_ID, OPTION_ID, promoCode, false, "CARD", null, null);
    }

    /** AC-4: 3-month Bronze 50 AZN with Bakcell20 -> discount 10.00, final 40.00 */
    @Test
    void quote_PercentDiscount_Applies20Percent() {
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));
        when(grantRepository.findByCampaignIdAndUserId(percentCampaign.getId(), USER_ID)).thenReturn(Optional.of(granted));
        when(translationRepository.findByCampaignId(any())).thenReturn(List.of());

        PromoQuoteResponse r = quote("Bakcell20");

        assertEquals(0, r.getPromo().getDiscountAmount().compareTo(new BigDecimal("10.00")));
        assertEquals(0, r.getFinalAmount().compareTo(new BigDecimal("40.00")));
        assertEquals(0, r.getBaseAmount().compareTo(new BigDecimal("50.00")));
        assertEquals("Bakcell20", r.getPromo().getCode());
        assertEquals(PACKAGE_ID, r.getPackageId());
        assertEquals(OPTION_ID, r.getOptionId());
    }

    @Test
    void quote_MaxDiscountCapsAmount() {
        percentCampaign.setMaxDiscountAmount(BigDecimal.valueOf(5));
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));
        when(grantRepository.findByCampaignIdAndUserId(percentCampaign.getId(), USER_ID)).thenReturn(Optional.of(granted));
        when(translationRepository.findByCampaignId(any())).thenReturn(List.of());

        PromoQuoteResponse r = quote("Bakcell20");

        assertEquals(0, r.getPromo().getDiscountAmount().compareTo(new BigDecimal("5.00")));
        assertEquals(0, r.getFinalAmount().compareTo(new BigDecimal("45.00")));
    }

    /** BR-2: fixed AZN discount */
    @Test
    void quote_FixedDiscount_SubtractsFixedAmount() {
        percentCampaign.setDiscountType(PromoDiscountType.FIXED);
        percentCampaign.setDiscountValue(BigDecimal.valueOf(7));
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));
        when(grantRepository.findByCampaignIdAndUserId(percentCampaign.getId(), USER_ID)).thenReturn(Optional.of(granted));
        when(translationRepository.findByCampaignId(any())).thenReturn(List.of());

        PromoQuoteResponse r = quote("Bakcell20");

        assertEquals(0, r.getPromo().getDiscountAmount().compareTo(new BigDecimal("7.00")));
        assertEquals(0, r.getFinalAmount().compareTo(new BigDecimal("43.00")));
    }

    /** B8: lookup is case-insensitive, display form preserved. */
    @Test
    void quote_LowercaseCode_Works() {
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));
        when(grantRepository.findByCampaignIdAndUserId(percentCampaign.getId(), USER_ID)).thenReturn(Optional.of(granted));
        when(translationRepository.findByCampaignId(any())).thenReturn(List.of());

        PromoQuoteResponse r = quote("bakcell20");

        assertEquals("Bakcell20", r.getPromo().getCode());
        assertEquals(0, r.getFinalAmount().compareTo(new BigDecimal("40.00")));
    }

    /** B10/MO-8: FitCoin applies after promo (40 - 16 = 24). */
    @Test
    void quote_FitCoin_AppliesAfterPromo() {
        when(paymentGrpcClient.getCoinWallet(anyLong())).thenReturn(
                new PaymentGrpcClient.CoinWalletSnapshot(new BigDecimal("320"), new BigDecimal("16.00"), null));
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));
        when(grantRepository.findByCampaignIdAndUserId(percentCampaign.getId(), USER_ID)).thenReturn(Optional.of(granted));
        when(translationRepository.findByCampaignId(any())).thenReturn(List.of());

        PromoQuoteResponse r = quoteService.quote(PACKAGE_ID, OPTION_ID, "Bakcell20", true, "CARD", null, null);

        assertEquals(0, r.getFinalAmount().compareTo(new BigDecimal("24.00")));
        assertEquals(0, r.getFitCoin().getUsedAmount().compareTo(new BigDecimal("16.00")));
        assertEquals(0, r.getFitCoin().getBalanceAmount().compareTo(new BigDecimal("16.00")));
        assertEquals(0, r.getFitCoin().getBalanceCoins().compareTo(new BigDecimal("320")));
    }

    /** B9: installments echo provider/months with server-side monthly math. */
    @Test
    void quote_Installment_ReturnsSchedule() {
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));
        when(grantRepository.findByCampaignIdAndUserId(percentCampaign.getId(), USER_ID)).thenReturn(Optional.of(granted));
        when(translationRepository.findByCampaignId(any())).thenReturn(List.of());

        PromoQuoteResponse r = quoteService.quote(PACKAGE_ID, OPTION_ID, "Bakcell20", false,
                "INSTALLMENT", "TAMKART", 6);

        assertNotNull(r.getInstallments());
        assertEquals("TAMKART", r.getInstallments().getProvider());
        assertEquals(6, r.getInstallments().getMonths());
        assertEquals(0, r.getInstallments().getMonthlyAmount().compareTo(new BigDecimal("6.67")));
    }

    /** NFR-1: knowing the code text gives no discount - must have a grant. */
    @Test
    void quote_NoGrant_ThrowsNotEligible() {
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));
        when(grantRepository.findByCampaignIdAndUserId(percentCampaign.getId(), USER_ID)).thenReturn(Optional.empty());

        BadRequestException ex = assertThrows(BadRequestException.class, () -> quote("Bakcell20"));
        assertEquals("error.promo_not_eligible", ex.getMessage());
    }

    /** NFR-1: code lookup first - unknown code is not eligible. */
    @Test
    void quote_UnknownCode_ThrowsNotEligible() {
        when(campaignRepository.findByCodeNormalized("UNKNOWN")).thenReturn(Optional.empty());

        BadRequestException ex = assertThrows(BadRequestException.class, () -> quote("UNKNOWN"));
        assertEquals("error.promo_not_eligible", ex.getMessage());
    }

    /** AC-15 / BR-8: already redeemed on this account. */
    @Test
    void quote_AlreadyRedeemed_ThrowsAlreadyUsed() {
        granted.setStatus(PromoGrantStatus.REDEEMED);
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));
        when(grantRepository.findByCampaignIdAndUserId(percentCampaign.getId(), USER_ID)).thenReturn(Optional.of(granted));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> quote("Bakcell20"));
        assertEquals("error.promo_already_used", ex.getMessage());
    }

    /** AC-12: paused campaign hides the code. */
    @Test
    void quote_PausedCampaign_ThrowsNotActive() {
        percentCampaign.setStatus(PromoCampaignStatus.PAUSED);
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> quote("Bakcell20"));
        assertEquals("error.promo_not_active", ex.getMessage());
    }

    /** BR-24: ended campaign never reusable. */
    @Test
    void quote_EndedCampaign_ThrowsExpired() {
        percentCampaign.setEndsAt(OffsetDateTime.now().minusDays(1));
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> quote("Bakcell20"));
        assertEquals("error.promo_expired", ex.getMessage());
    }

    @Test
    void quote_LimitReached_ThrowsLimitReached() {
        percentCampaign.setTotalLimit(10);
        percentCampaign.setRedeemedCount(10);
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> quote("Bakcell20"));
        assertEquals("error.promo_limit_reached", ex.getMessage());
    }

    /** BR: codes never apply to plans outside plan_ids. */
    @Test
    void quote_PlanNotAllowed_ThrowsPlanNotAllowed() {
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> quoteService.quote("99", OPTION_ID, "Bakcell20", false, "CARD", null, null));
        assertEquals("error.promo_plan_not_allowed", ex.getMessage());
    }

    @Test
    void quote_InstallmentNotAllowed_ThrowsPaymentTypeNotAllowed() {
        percentCampaign.setAllowInstallments(false);
        when(campaignRepository.findByCodeNormalized("BAKCELL20")).thenReturn(Optional.of(percentCampaign));
        when(grantRepository.findByCampaignIdAndUserId(percentCampaign.getId(), USER_ID)).thenReturn(Optional.of(granted));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> quoteService.quote(PACKAGE_ID, OPTION_ID, "Bakcell20", false, "INSTALLMENT", "TAMKART", 6));
        assertEquals("error.promo_payment_type_not_allowed", ex.getMessage());
    }

    @Test
    void quote_NoPromoCode_ReturnsBaseAmount() {
        PromoQuoteResponse r = quote(null);
        assertEquals(0, r.getFinalAmount().compareTo(new BigDecimal("50.00")));
        assertNull(r.getPromo());
    }

    /** NFR-2: rate limit kicks in */
    @Test
    void quote_RateLimited_Throws() {
        when(rateLimitService.tryConsumeQuote(anyLong())).thenReturn(false);

        BadRequestException ex = assertThrows(BadRequestException.class, () -> quote(null));
        assertEquals("error.promo_rate_limited", ex.getMessage());
    }
}
