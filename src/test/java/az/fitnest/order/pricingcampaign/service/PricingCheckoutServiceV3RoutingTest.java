package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.dto.epoint.EpointPaymentRequest;
import az.fitnest.order.dto.epoint.EpointResponse;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.grpc.PaymentGrpcClient;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.pricingcampaign.dto.CheckoutCreateRequestV2;
import az.fitnest.order.pricingcampaign.dto.CheckoutCreateRequestV3;
import az.fitnest.order.pricingcampaign.dto.CheckoutCreateResponseV2;
import az.fitnest.order.pricingcampaign.dto.PricingDto;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.repository.OrderRepository;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/** Verifies BF checkout picks the right rail while always charging the pre-priced BF total. */
@ExtendWith(MockitoExtension.class)
class PricingCheckoutServiceV3RoutingTest {

    private static final BigDecimal BF_TOTAL = new BigDecimal("1890.00");

    @Mock SubscriptionPackageRepository packageRepository;
    @Mock OrderRepository orderRepository;
    @Mock PaymentGrpcClient paymentGrpcClient;
    @Mock PricingCampaignService campaignService;
    @InjectMocks PricingCheckoutService service;

    @BeforeEach
    void setUp() {
        SubscriptionPackage pkg = new SubscriptionPackage();
        pkg.setName("Premium");
        pkg.setCurrency("AZN");
        pkg.setIsActive(true);
        PackageOption option = new PackageOption();
        option.setDurationMonths(12);
        option.setPriceStandard(new BigDecimal("2700.00"));
        pkg.setOptions(Set.of(option));
        // Entities expose id via BaseAuditableEntity; reflect-free approach: stub through Mockito spies is
        // overkill, so set the ids with the generated setters.
        pkg.setId(4L);
        option.setId(102L);

        lenient().when(packageRepository.findById(4L)).thenReturn(Optional.of(pkg));
        lenient().when(campaignService.applicableCampaign(anyLong())).thenReturn(Optional.empty());
        lenient().when(campaignService.computePricing(any(), anyInt(), nullable(PricingCampaign.class))).thenReturn(new PricingDto(
                new BigDecimal("2700.00"), BF_TOTAL, new BigDecimal("810.00"), new BigDecimal("30"),
                new BigDecimal("157.50"), new BigDecimal("225.00"), true, UUID.randomUUID()));
        lenient().when(paymentGrpcClient.initiatePayment(any())).thenReturn(
                EpointResponse.builder().status("success").redirect_url("https://bank/pay").build());
    }

    private static CheckoutCreateRequestV3 req(String type, String provider, Integer months, Integer isInstallment) {
        return new CheckoutCreateRequestV3(4L, 102L, false, isInstallment, BF_TOTAL, type, provider, months);
    }

    private EpointPaymentRequest captured() {
        ArgumentCaptor<EpointPaymentRequest> c = ArgumentCaptor.forClass(EpointPaymentRequest.class);
        verify(paymentGrpcClient).initiatePayment(c.capture());
        return c.getValue();
    }

    @Test
    void omittedPaymentTypeStaysOnEpoint() {
        CheckoutCreateResponseV2 res = service.createV3(1L, req(null, null, null, 1));
        EpointPaymentRequest sent = captured();
        assertEquals("epoint", res.payment().provider());
        assertEquals("epoint", sent.provider());
        assertEquals(1, sent.is_installment());
        assertEquals(BF_TOTAL.doubleValue(), sent.amount());
    }

    @Test
    void tamkartRoutesToAbbWithNumericOrderIdAndBfAmount() {
        CheckoutCreateResponseV2 res = service.createV3(1L, req("INSTALLMENT", "tamkart", 6, 1));
        EpointPaymentRequest sent = captured();
        assertEquals("abb", res.payment().provider());
        assertEquals("abb", sent.provider());
        assertEquals(6, sent.installmentMonths());
        assertEquals(0, sent.is_installment(), "is_installment is Epoint-only");
        assertEquals(BF_TOTAL.doubleValue(), sent.amount());
        assertTrue(sent.order_id().matches("\\d{6,32}"), "ABB ORDER must be numeric");
        assertEquals(sent.order_id(), res.orderId());
        assertTrue(sent.other_attr().get(0).toString().contains("campaignId:"));
    }

    @Test
    void bolkartRoutesToBob() {
        CheckoutCreateResponseV2 res = service.createV3(1L, req("INSTALLMENT", "BOLKART", 3, null));
        EpointPaymentRequest sent = captured();
        assertEquals("bob", res.payment().provider());
        assertEquals("bob", sent.provider());
        assertEquals(3, sent.installmentMonths());
        assertTrue(sent.order_id().startsWith("ord_"));
    }

    @Test
    void applePayRoutesNativelyWithEpointPaymentIdAndBfAmount() {
        when(paymentGrpcClient.initiatePayment(any())).thenReturn(
                EpointResponse.builder().status("success").transaction("epoint-pay-123").build());

        CheckoutCreateResponseV2 res = service.createV3(1L, req("apple_pay", null, null, 1));
        EpointPaymentRequest sent = captured();
        assertEquals("apple_pay", res.payment().provider());
        assertEquals("apple_pay", sent.provider());
        assertEquals(0, sent.is_installment(), "is_installment is Epoint-card-only");
        assertEquals(BF_TOTAL.doubleValue(), sent.amount());
        assertTrue(sent.order_id().startsWith("ord_"));
        assertEquals(sent.order_id(), res.orderId());
        assertTrue(sent.other_attr().get(0).toString().contains("campaignId:"));
        assertEquals("epoint-pay-123", res.payment().paymentIntentClientSecret());
        assertNull(res.payment().paymentUrl());
        assertEquals("success", res.payment().status());
    }

    @Test
    void googlePayRoutesNativelyWithEpointPaymentIdAndBfAmount() {
        when(paymentGrpcClient.initiatePayment(any())).thenReturn(
                EpointResponse.builder().status("success").transaction("epoint-pay-456").build());

        CheckoutCreateResponseV2 res = service.createV3(1L, req("google_pay", null, null, 1));
        EpointPaymentRequest sent = captured();
        assertEquals("google_pay", res.payment().provider());
        assertEquals("google_pay", sent.provider());
        assertEquals(0, sent.is_installment(), "is_installment is Epoint-card-only");
        assertEquals(BF_TOTAL.doubleValue(), sent.amount());
        assertTrue(sent.order_id().startsWith("ord_"));
        assertEquals(sent.order_id(), res.orderId());
        assertTrue(sent.other_attr().get(0).toString().contains("campaignId:"));
        assertEquals("epoint-pay-456", res.payment().paymentIntentClientSecret());
        assertNull(res.payment().paymentUrl());
        assertEquals("success", res.payment().status());
    }

    /** Regression guard for the "never edit published endpoints" rule: v2 stays Epoint-only, no new gRPC fields. */
    @Test
    void v2CreateStaysEpointOnly() {
        CheckoutCreateResponseV2 res = service.create(1L, new CheckoutCreateRequestV2(4L, 102L, false, 1, BF_TOTAL));
        EpointPaymentRequest sent = captured();
        assertEquals("epoint", res.payment().provider());
        assertNull(sent.provider());
        assertNull(sent.installmentMonths());
        assertEquals(1, sent.is_installment());
        assertTrue(sent.order_id().startsWith("ord_"));
    }

    @Test
    void invalidCombosAreBadRequest() {
        assertThrows(BadRequestException.class, () -> service.createV3(1L, req("INSTALLMENT", "TAMKART", null, null)));
        assertThrows(BadRequestException.class, () -> service.createV3(1L, req("INSTALLMENT", null, 6, null)));
        assertThrows(BadRequestException.class, () -> service.createV3(1L, req("INSTALLMENT", "FOO", 6, null)));
        assertThrows(BadRequestException.class, () -> service.createV3(1L, req("SAVED_CARD", null, null, null)));
        assertThrows(BadRequestException.class, () -> service.createV3(1L, req("BITCOIN", null, null, null)));
        verifyNoInteractions(paymentGrpcClient);
    }
}
