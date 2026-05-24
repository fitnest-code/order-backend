package az.fitnest.order.grpc;

import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.model.entity.PlanBenefit;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class SubscriptionPackageGrpcServiceImpl extends SubscriptionPackageServiceGrpc.SubscriptionPackageServiceImplBase {

    private final SubscriptionPackageRepository packageRepository;
    private final az.fitnest.order.service.impl.UserSubscriptionService userSubscriptionService;

    @Override
    public void getPackageNamesByIds(GetPackageNamesByIdsRequest request, StreamObserver<GetPackageNamesByIdsResponse> responseObserver) {
        try {
            var packages = packageRepository.findAllById(request.getPackageIdsList());
            GetPackageNamesByIdsResponse.Builder responseBuilder = GetPackageNamesByIdsResponse.newBuilder();
            for (SubscriptionPackage pkg : packages) {
                PackageNameInfo.Builder pkgBuilder = PackageNameInfo.newBuilder()
                        .setPackageId(pkg.getId())
                        .setName(pkg.getName() != null ? pkg.getName() : "");
                responseBuilder.addPackages(pkgBuilder.build());
            }
            responseObserver.onNext(responseBuilder.build());
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to fetch package names by IDs: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
            return;
        }
        responseObserver.onCompleted();
    }

    @Override
    public void checkIn(az.fitnest.order.grpc.CheckInRequest request, StreamObserver<az.fitnest.order.grpc.CheckInResponse> responseObserver) {
        try {
            boolean success = userSubscriptionService.checkIn(request.getUserId(), request.getGymId(), request.getConsumeFrozen());
            responseObserver.onNext(az.fitnest.order.grpc.CheckInResponse.newBuilder()
                    .setSuccess(success)
                    .setMessage("Checked in successfully")
                    .build());
        } catch (Exception e) {
            responseObserver.onNext(az.fitnest.order.grpc.CheckInResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage(e.getMessage() != null ? e.getMessage() : "Check-in failed")
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void getGymPlans(GetGymPlansRequest request, StreamObserver<GetGymPlansResponse> responseObserver) {
        var packages = packageRepository.findByIsActiveTrue();
        GetGymPlansResponse.Builder responseBuilder = GetGymPlansResponse.newBuilder();
        for (SubscriptionPackage pkg : packages) {
            responseBuilder.addPackages(mapPackageToGrpc(pkg));
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void checkPlanExists(CheckPlanExistsRequest request, StreamObserver<CheckPlanExistsResponse> responseObserver) {
        var opt = packageRepository.findById(request.getPackageId());
        if (opt.isPresent()) {
            responseObserver.onNext(CheckPlanExistsResponse.newBuilder()
                    .setExists(true)
                    .setIsActive(opt.get().getIsActive() != null && opt.get().getIsActive())
                    .build());
        } else {
            responseObserver.onNext(CheckPlanExistsResponse.newBuilder()
                    .setExists(false)
                    .setIsActive(false)
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void getPlansByIds(GetPlansByIdsRequest request, StreamObserver<GetPlansByIdsResponse> responseObserver) {
        try {
            var packages = packageRepository.findAllByIdWithOptions(request.getPackageIdsList());
            for (SubscriptionPackage pkg : packages) {
                if (pkg.getBenefits() != null) {
                    pkg.getBenefits().size();
                }
            }
            GetPlansByIdsResponse.Builder responseBuilder = GetPlansByIdsResponse.newBuilder();
            for (SubscriptionPackage pkg : packages) {
                responseBuilder.addPackages(mapPackageToGrpc(pkg));
            }
            responseObserver.onNext(responseBuilder.build());
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to fetch plans by IDs: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
            return;
        }
        responseObserver.onCompleted();
    }

    @Override
    public void checkOptionInPackageExists(az.fitnest.order.grpc.CheckOptionInPackageExistsRequest request, StreamObserver<az.fitnest.order.grpc.CheckOptionInPackageExistsResponse> responseObserver) {
        boolean exists = false;
        try {
            var opt = packageRepository.findFullById(request.getPackageId());
            if (opt.isPresent()) {
                var pkg = opt.get();
                if (pkg.getOptions() != null) {
                    exists = pkg.getOptions().stream().anyMatch(option -> option.getId().equals(request.getOptionId()));
                }
            }
            az.fitnest.order.grpc.CheckOptionInPackageExistsResponse response = az.fitnest.order.grpc.CheckOptionInPackageExistsResponse.newBuilder()
                .setExists(exists)
                .build();
            responseObserver.onNext(response);
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to check option in package: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
            return;
        }
        responseObserver.onCompleted();
    }

    @Override
    public void getOptionDetails(GetOptionDetailsRequest request, StreamObserver<GetOptionDetailsResponse> responseObserver) {
        try {
            var opt = packageRepository.findFullById(request.getPackageId());
            if (opt.isPresent()) {
                var pkg = opt.get();
                if (pkg.getOptions() != null) {
                    var option = pkg.getOptions().stream()
                        .filter(o -> o.getId().equals(request.getOptionId()))
                        .findFirst();
                    if (option.isPresent()) {
                        var optEntity = option.get();
                        double amount = optEntity.getPriceDiscounted() != null ?
                            optEntity.getPriceDiscounted().doubleValue() :
                            (optEntity.getPriceStandard() != null ? optEntity.getPriceStandard().doubleValue() : 0.0);
                        String currency = pkg.getCurrency() != null ? pkg.getCurrency() : "AZN";
                        GetOptionDetailsResponse response = GetOptionDetailsResponse.newBuilder()
                            .setAmount(amount)
                            .setCurrency(currency)
                            .setDurationMonths(optEntity.getDurationMonths() != null ? optEntity.getDurationMonths() : 0)
                            .build();
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                        return;
                    }
                }
            }
            responseObserver.onError(io.grpc.Status.NOT_FOUND
                .withDescription("Option or package not found")
                .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to get option details: " + e.getMessage())
                .withCause(e)
                .asRuntimeException());
        }
    }

    private SubscriptionPackageInfo mapPackageToGrpc(SubscriptionPackage pkg) {
        SubscriptionPackageInfo.Builder pkgBuilder = SubscriptionPackageInfo.newBuilder()
                .setPackageId(pkg.getId())
                .setName(pkg.getName() != null ? pkg.getName() : "")
                .setCurrency(pkg.getCurrency() != null ? pkg.getCurrency() : "AZN")
                .setIsActive(pkg.getIsActive() != null && pkg.getIsActive());

        if (pkg.getBenefits() != null) {
            for (az.fitnest.order.model.entity.PlanBenefit benefit : pkg.getBenefits()) {
                if (benefit.getDescription() != null) {
                    pkgBuilder.addBenefits(benefit.getDescription());
                }
            }
        }

        if (pkg.getOptions() != null) {
            for (PackageOption opt : pkg.getOptions()) {
                SubscriptionPackageOption.Builder optBuilder = SubscriptionPackageOption.newBuilder()
                        .setDurationMonths(opt.getDurationMonths() != null ? opt.getDurationMonths() : 0)
                        .setPriceStandard(opt.getPriceStandard() != null ? opt.getPriceStandard().toPlainString() : "0")
                        .setPriceDiscounted(opt.getPriceDiscounted() != null ? opt.getPriceDiscounted().toPlainString() : "")
                        .setEntryLimit(pkg.getEntryLimit() != null ? pkg.getEntryLimit() : 0)
                        .setFreezeDays(0);

                pkgBuilder.addOptions(optBuilder.build());
            }
        }
        return pkgBuilder.build();
    }
}
