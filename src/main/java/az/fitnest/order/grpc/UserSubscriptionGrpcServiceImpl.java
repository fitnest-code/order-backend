package az.fitnest.order.grpc;

import az.fitnest.order.dto.ActiveSubscriptionResponse;
import az.fitnest.order.service.impl.UserSubscriptionService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.List;

@GrpcService
@RequiredArgsConstructor
public class UserSubscriptionGrpcServiceImpl extends az.fitnest.order.grpc.UserSubscriptionServiceGrpc.UserSubscriptionServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(UserSubscriptionGrpcServiceImpl.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private final UserSubscriptionService subscriptionService;

    @Override
    public void getActiveSubscription(GetActiveSubscriptionRequest request, StreamObserver<az.fitnest.order.grpc.ActiveSubscriptionResponse> responseObserver) {
        try {
            Long userId = request.getUserId();
            ActiveSubscriptionResponse dto = subscriptionService.getActiveSubscription(userId);

            az.fitnest.order.grpc.ActiveSubscriptionResponse.Builder grpcResponse = az.fitnest.order.grpc.ActiveSubscriptionResponse.newBuilder();

            if (dto.status() != null) {
                grpcResponse.setSubscriptionStatus(dto.status());
            }
            if (dto.subscription() != null) {
                var sub = dto.subscription();
                if (sub.packageName() != null) grpcResponse.setPackageName(sub.packageName());
                if (sub.packageId() != null) grpcResponse.setPackageId(Long.parseLong(sub.packageId()));
                if (sub.totalLimit() != null) grpcResponse.setTotalLimit(sub.totalLimit());
                if (sub.remainingLimit() != null) grpcResponse.setRemainingLimit(sub.remainingLimit());
                if (sub.endAt() != null) grpcResponse.setExpiresAt(sub.endAt().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            }
            responseObserver.onNext(grpcResponse.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to get active subscription: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void getUserIdsByPackageId(az.fitnest.order.grpc.GetUserIdsByPackageIdRequest request, StreamObserver<az.fitnest.order.grpc.GetUserIdsByPackageIdResponse> responseObserver) {
        try {
            Long packageId = request.getPackageId();
            List<Long> userIds = subscriptionService.getUserIdsByPackageId(packageId);
            az.fitnest.order.grpc.GetUserIdsByPackageIdResponse response = az.fitnest.order.grpc.GetUserIdsByPackageIdResponse.newBuilder()
                .addAllUserIds(userIds)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to get user IDs: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }

    @Override
    public void getUserIdsByDurationMonths(az.fitnest.order.grpc.GetUserIdsByDurationMonthsRequest request, StreamObserver<az.fitnest.order.grpc.GetUserIdsByPackageIdResponse> responseObserver) {
        try {
            int durationMonths = request.getDurationMonths();
            List<Long> userIds = subscriptionService.getUserIdsByDurationMonths(durationMonths);
            az.fitnest.order.grpc.GetUserIdsByPackageIdResponse response = az.fitnest.order.grpc.GetUserIdsByPackageIdResponse.newBuilder()
                .addAllUserIds(userIds)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to get user IDs by duration: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }

    @Override
    public void getUserIdsByType(az.fitnest.order.grpc.GetUserIdsByTypeRequest request, StreamObserver<az.fitnest.order.grpc.GetUserIdsByPackageIdResponse> responseObserver) {
        try {
            String type = request.getType();
            List<Long> userIds = subscriptionService.getUserIdsByType(type);
            az.fitnest.order.grpc.GetUserIdsByPackageIdResponse response = az.fitnest.order.grpc.GetUserIdsByPackageIdResponse.newBuilder()
                .addAllUserIds(userIds)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to get user IDs by type: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }

    @Override
    public void assignSubscriptionToUser(az.fitnest.order.grpc.AssignSubscriptionToUserRequest request, StreamObserver<az.fitnest.order.grpc.AssignSubscriptionToUserResponse> responseObserver) {
        log.info("[gRPC] Received AssignSubscriptionToUser request: userId={}, planId={}, optionId={}", request.getUserId(), request.getPlanId(), request.getOptionId());
        try {
            var assignRequest = az.fitnest.order.dto.AdminAssignSubscriptionRequest.builder()
                .userId(request.getUserId())
                .planId(request.getPlanId())
                .optionId(request.getOptionId())
                .autoPaymentEnabled(request.getAutoPaymentEnabled())
                .build();
            var result = subscriptionService.assignSubscriptionToUser(assignRequest);
            az.fitnest.order.grpc.AssignSubscriptionToUserResponse response = az.fitnest.order.grpc.AssignSubscriptionToUserResponse.newBuilder()
                .setSubscriptionId(result.subscriptionId())
                .setUserId(result.userId())
                .build();
            log.info("[gRPC] Successfully assigned subscription: subscriptionId={}, userId={}, planId={}, optionId={}", result.subscriptionId(), result.userId(), request.getPlanId(), request.getOptionId());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("[gRPC] Failed to assign subscription: userId={}, planId={}, optionId={}", request.getUserId(), request.getPlanId(), request.getOptionId(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to assign subscription: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }

    @Override
    public void getFilteredUserIds(az.fitnest.order.grpc.GetFilteredUserIdsRequest request, StreamObserver<az.fitnest.order.grpc.GetUserIdsByPackageIdResponse> responseObserver) {
        try {
            List<Long> userIds = subscriptionService.getFilteredUserIds(request);
            az.fitnest.order.grpc.GetUserIdsByPackageIdResponse response = az.fitnest.order.grpc.GetUserIdsByPackageIdResponse.newBuilder()
                    .addAllUserIds(userIds)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("[gRPC] Failed to get filtered user IDs", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to get filtered user IDs: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void getSubscriptionStatistics(az.fitnest.order.grpc.GetSubscriptionStatisticsRequest request, StreamObserver<az.fitnest.order.grpc.SubscriptionStatisticsResponse> responseObserver) {
        try {
            az.fitnest.order.grpc.SubscriptionStatisticsResponse response = subscriptionService.getSubscriptionStatistics();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("[gRPC] Failed to get subscription statistics", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to get statistics: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }
}
