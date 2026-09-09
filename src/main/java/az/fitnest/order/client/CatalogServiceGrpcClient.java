package az.fitnest.order.client;

import az.fitnest.catalog.grpc.CountGymsByPackageRequest;
import az.fitnest.catalog.grpc.CountGymsByPackageResponse;
import az.fitnest.catalog.grpc.GymServiceGrpc;
import az.fitnest.catalog.grpc.GymSupportsPlanRequest;
import az.fitnest.catalog.grpc.GymSupportsPlanResponse;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CatalogServiceGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogServiceGrpcClient.class);

    @GrpcClient("catalog-backend")
    private GymServiceGrpc.GymServiceBlockingStub blockingStub;

    public boolean gymSupportsPlan(Long gymId, Long planId) {
        GymSupportsPlanRequest request = GymSupportsPlanRequest.newBuilder()
                .setGymId(gymId)
                .setPlanId(planId)
                .build();
        GymSupportsPlanResponse response = blockingStub.gymSupportsPlan(request);
        return response.getSupported();
    }

    public long countGymsByPackage(Long packageId) {
        if (packageId == null) {
            return 0;
        }
        try {
            CountGymsByPackageResponse response = blockingStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .countGymsByPackage(
                            CountGymsByPackageRequest.newBuilder()
                                    .setPackageId(packageId)
                                    .build()
                    );
            return response.getGymCount();
        } catch (Exception e) {
            log.warn("Failed to count gyms for packageId={}: {}", packageId, e.getMessage());
            return 0;
        }
    }
}
