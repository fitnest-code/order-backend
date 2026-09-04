package az.fitnest.order.grpc;

import az.fitnest.payment.grpc.CreatePaymentRequest;
import az.fitnest.payment.grpc.CreatePaymentResponse;
import az.fitnest.payment.grpc.GetCoinWalletRequest;
import az.fitnest.payment.grpc.GetCoinWalletResponse;
import az.fitnest.payment.grpc.PaymentServiceGrpc;
import az.fitnest.order.dto.epoint.EpointPaymentRequest;
import az.fitnest.order.dto.epoint.EpointResponse;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class PaymentGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGrpcClient.class);
    private static final DateTimeFormatter VALIDITY_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @GrpcClient("payment-backend")
    private PaymentServiceGrpc.PaymentServiceBlockingStub paymentServiceStub;

    public EpointResponse initiatePayment(EpointPaymentRequest request) {
        CreatePaymentRequest.Builder grpcRequestBuilder = CreatePaymentRequest.newBuilder()
                .setOrderId(request.order_id())
                .setAmount(request.amount())
                .setCurrency(request.currency())
                .setDescription(request.description() != null ? request.description() : "")
                .setLanguage(request.language() != null ? request.language() : "az")
                .setIsInstallment(request.is_installment() != null ? request.is_installment() : 0)
                .setRefund(request.refund() != null ? request.refund() : 0);

        if (request.other_attr() != null) {
            List<String> otherAttrs = request.other_attr().stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
            grpcRequestBuilder.addAllOtherAttr(otherAttrs);
        }

        CreatePaymentRequest grpcRequest = grpcRequestBuilder.build();

        CreatePaymentResponse grpcResponse = paymentServiceStub.createPayment(grpcRequest);

        return EpointResponse.builder()
                .status(grpcResponse.getStatus())
                .redirect_url(grpcResponse.getRedirectUrl())
                .transaction(grpcResponse.getTransactionId())
                .message(grpcResponse.getMessage())
                .build();
    }

    public az.fitnest.payment.grpc.PayWithCardResponse payWithCard(Long userId, String cardId, Long packageId, Long optionId) {
        az.fitnest.payment.grpc.PayWithCardRequest request = az.fitnest.payment.grpc.PayWithCardRequest.newBuilder()
                .setUserId(userId)
                .setCardId(cardId)
                .setPackageId(packageId)
                .setOptionId(optionId)
                .build();
        return paymentServiceStub.payWithCard(request);
    }

    public List<az.fitnest.payment.grpc.UserCardDto> getUserCards(Long userId) {
        az.fitnest.payment.grpc.GetUserCardsRequest request = az.fitnest.payment.grpc.GetUserCardsRequest.newBuilder()
                .setUserId(userId)
                .build();
        az.fitnest.payment.grpc.GetUserCardsResponse response = paymentServiceStub.getUserCards(request);
        return response.getCardsList();
    }

    public CoinWalletSnapshot getCoinWallet(Long userId) {
        try {
            GetCoinWalletResponse response = paymentServiceStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getCoinWallet(GetCoinWalletRequest.newBuilder().setUserId(userId).build());
            return CoinWalletSnapshot.from(response);
        } catch (Exception e) {
            log.warn("Failed to fetch coin wallet for userId={}: {}", userId, e.getMessage());
            return CoinWalletSnapshot.empty();
        }
    }

    public record CoinWalletSnapshot(
            BigDecimal coinBalance,
            BigDecimal aznEquivalent,
            String validityDate
    ) {
        static CoinWalletSnapshot from(GetCoinWalletResponse response) {
            BigDecimal balance = parseDecimal(response.getCoinBalance());
            BigDecimal azn = parseDecimal(response.getAznEquivalent());
            String validity = null;
            if (response.getValidityDate() != null && !response.getValidityDate().isBlank()) {
                try {
                    validity = LocalDateTime.parse(response.getValidityDate()).format(VALIDITY_DATE_FORMAT);
                } catch (Exception e) {
                    log.warn("Failed to parse coin validity date '{}': {}", response.getValidityDate(), e.getMessage());
                }
            }
            return new CoinWalletSnapshot(balance, azn, validity);
        }

        private static BigDecimal parseDecimal(String value) {
            if (value == null || value.isBlank()) {
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }

        public static CoinWalletSnapshot empty() {
            return new CoinWalletSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
    }
}
