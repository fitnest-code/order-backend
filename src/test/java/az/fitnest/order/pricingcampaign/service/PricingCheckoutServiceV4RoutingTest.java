package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.dto.epoint.EpointPaymentRequest;
import az.fitnest.order.dto.epoint.EpointResponse;
import az.fitnest.order.exception.BadRequestException;
import az.fitnest.order.grpc.PaymentGrpcClient;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.pricingcampaign.dto.CheckoutCreateRequestV3;
import az.fitnest.order.pricingcampaign.dto.CheckoutCreateRequestV4;
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

/** v4 adds KAPITAL without changing the frozen v3 contract. */
@ExtendWith(MockitoExtension.class)
class PricingCheckoutServiceV4RoutingTest {

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
        pkg.setId(4L);
        option.setId(102L);

        lenient().when(packageRepository.findById(4L)).thenReturn(Optional.of(pkg));
        lenient().when(campaignService.applicableCampaign(anyLong())).thenReturn(Optional.empty());
        lenient().when(campaignService.computePricing(any(), anyInt(), nullable(PricingCampaign.class))).thenReturn(new PricingDto(
                new BigDecimal("2700.00"), BF_TOTAL, new BigDecimal("810.00"), new BigDecimal("30"),
                new BigDecimal("157.50"), new BigDecimal("225.00"), true, UUID.randomUUID()));
        lenient().when(paymentGrpcClient.initiatePayment(any())).thenReturn(
                EpointResponse.builder().status("success").redirect_url("https://txpgtst.kapitalbank.az/flex?id=1&password=x").build());
    }

    private static CheckoutCreateRequestV4 reqV4(String type, String provider, Integer months) {
        return new CheckoutCreateRequestV4(4L, 102L, false, null, BF_TOTAL, type, provider, months, null);
    }

    private static CheckoutCreateRequestV3 reqV3(String type, String provider, Integer months) {
        return new CheckoutCreateRequestV3(4L, 102L, false, null, BF_TOTAL, type, provider, months, null);
    }

    private EpointPaymentRequest captured() {
        ArgumentCaptor<EpointPaymentRequest> c = ArgumentCaptor.forClass(EpointPaymentRequest.class);
        verify(paymentGrpcClient).initiatePayment(c.capture());
        return c.getValue();
    }

    @Test
    void kapitalCardRoutesToKapitalProvider() {
        CheckoutCreateResponseV2 res = service.createV4(1L, reqV4("KAPITAL", null, null));
        EpointPaymentRequest sent = captured();
        assertEquals("kapital", res.payment().provider());
        assertEquals("kapital", sent.provider());
        assertNull(sent.installmentMonths());
        assertEquals(BF_TOTAL.doubleValue(), sent.amount());
        assertTrue(sent.order_id().startsWith("ord_"));
    }

    @Test
    void kapitalTaksitRoutesWithMonths() {
        CheckoutCreateResponseV2 res = service.createV4(1L, reqV4("INSTALLMENT", "KAPITAL", 3));
        EpointPaymentRequest sent = captured();
        assertEquals("kapital", res.payment().provider());
        assertEquals("kapital", sent.provider());
        assertEquals(3, sent.installmentMonths());
        assertEquals(0, sent.is_installment(), "is_installment is Epoint-only");
    }

    @Test
    void v3StillRejectsKapitalPaymentType() {
        assertThrows(BadRequestException.class, () -> service.createV3(1L, reqV3("KAPITAL", null, null)));
        assertThrows(BadRequestException.class, () -> service.createV3(1L, reqV3("INSTALLMENT", "KAPITAL", 3)));
        verifyNoInteractions(paymentGrpcClient);
    }
}
