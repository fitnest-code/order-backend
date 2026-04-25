package az.fitnest.order.service.impl;

import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.dto.PackageOptionEntityDto;
import az.fitnest.order.dto.SubscriptionPackageWithOptionsRequest;
import az.fitnest.order.model.enums.BillingPeriod;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SubscriptionPackageAdminService {
            @Transactional(readOnly = true)
            public az.fitnest.order.dto.PaginatedResponse<az.fitnest.order.dto.AdminSubscriptionPackageResponse> getAllPackagesPagedResponse(int page, int size) {
                org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.max(1, size));
                org.springframework.data.domain.Page<az.fitnest.order.dto.AdminSubscriptionPackageResponse> result = packageRepository.findAllWithOptions(pageable)
                        .map(this::toAdminPackageResponse);
                return az.fitnest.order.dto.PaginatedResponse.of(result);
            }
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<az.fitnest.order.dto.AdminSubscriptionPackageResponse> getAllPackagesPaged(int page, int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.max(1, size));
        return packageRepository.findAllWithOptions(pageable)
                .map(this::toAdminPackageResponse);
    }

    private final SubscriptionPackageRepository packageRepository;

    @Transactional(readOnly = true)
    public List<az.fitnest.order.dto.AdminSubscriptionPackageResponse> getAllPackages() {
        return packageRepository.findAllWithOptions().stream()
                .map(this::toAdminPackageResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<az.fitnest.order.dto.PackageNameDto> getPackageNames() {
        return packageRepository.findAll().stream()
                .map(pkg -> az.fitnest.order.dto.PackageNameDto.builder()
                        .id(pkg.getId())
                        .name(pkg.getName())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public az.fitnest.order.dto.AdminSubscriptionPackageResponse getPackageById(Long packageId) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        return toAdminPackageResponse(pkg);
    }

    private az.fitnest.order.dto.AdminSubscriptionPackageResponse toAdminPackageResponse(SubscriptionPackage pkg) {
        List<az.fitnest.order.dto.AdminSubscriptionPackageResponse.AdminPackageOptionResponse> options =
                pkg.getOptions().stream()
                        .map(opt -> az.fitnest.order.dto.AdminSubscriptionPackageResponse.AdminPackageOptionResponse.builder()
                                .optionId(opt.getId())
                                .durationMonths(opt.getDurationMonths())
                                .priceStandard(opt.getPriceStandard())
                                .priceDiscounted(opt.getPriceDiscounted())
                                .entryLimit(opt.getEntryLimit())
                                .freezeDays(opt.getFreezeDays())
                                .build())
                        .toList();

        return az.fitnest.order.dto.AdminSubscriptionPackageResponse.builder()
                .packageId(pkg.getId())
                .name(pkg.getName())
                .isActive(pkg.getIsActive())
                .sortOrder(pkg.getSortOrder())
                .durationOptions(options)
                .build();
    }

    @Transactional
    public void addPackageWithOptions(SubscriptionPackageWithOptionsRequest request) {
        List<String> allowedNames = List.of("Bronze", "Silver", "Gold", "Platinum");
        if (request.name() == null || request.name().isBlank() || !allowedNames.contains(request.name())) {
            throw new az.fitnest.order.exception.BadRequestException("error.invalid_package_name");
        }
        SubscriptionPackage pkg = packageRepository.findAllWithOptions().stream()
            .filter(p -> p.getName().equals(request.name()))
            .findFirst()
            .orElse(null);

        if (pkg == null) {
            pkg = new SubscriptionPackage();
            pkg.setName(request.name());
            pkg.setCurrency("AZN");
            pkg.setBillingPeriod(az.fitnest.order.model.enums.BillingPeriod.ONE_TIME);
            pkg.setIsActive(request.isActive() != null ? request.isActive() : true);
            pkg.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 1);
            pkg.setPrice(BigDecimal.ZERO);
        }

        if (request.options() != null) {
            for (PackageOptionEntityDto dto : request.options()) {
                boolean exists = pkg.getOptions().stream().anyMatch(opt ->
                    Objects.equals(opt.getDurationMonths(), dto.durationMonths())
                );
                if (exists) {
                    throw new az.fitnest.order.exception.BadRequestException("error.duration_already_exists");
                }
                PackageOption opt = new PackageOption();
                opt.setSubscriptionPackage(pkg);
                opt.setDurationMonths(dto.durationMonths());
                opt.setPriceStandard(dto.priceStandard());
                opt.setPriceDiscounted(dto.priceDiscounted());
                opt.setEntryLimit(dto.entryLimit());
                opt.setFreezeDays(dto.freezeDays());
                if (dto.benefits() != null) {
                    List<az.fitnest.order.model.entity.PlanBenefit> benefits = new ArrayList<>();
                    for (az.fitnest.order.model.entity.PlanBenefit pb : dto.benefits()) {
                        benefits.add(pb);
                    }
                    opt.setBenefits(benefits);
                }
                pkg.getOptions().add(opt);
            }
        }
        if (!pkg.getOptions().isEmpty()) {
            PackageOption first = pkg.getOptions().iterator().next();
            if (first.getDurationMonths() != null && first.getDurationMonths() > 0 && first.getPriceStandard() != null) {
                BigDecimal monthly = first.getPriceStandard().divide(new BigDecimal(first.getDurationMonths()), 4, RoundingMode.HALF_UP);
                pkg.setPrice(monthly);
            }
        }
        packageRepository.save(pkg);
    }

    @Transactional
    public Long createPackage(String name, String currency, BillingPeriod billingPeriod, Boolean isActive) {
        if (name == null || name.isBlank()) {
            throw new az.fitnest.order.exception.BadRequestException("error.missing_field");
        }
        SubscriptionPackage pkg = new SubscriptionPackage();
        pkg.setName(name);
        pkg.setCurrency(currency != null ? currency : "AZN");
        pkg.setBillingPeriod(billingPeriod != null ? billingPeriod : BillingPeriod.MONTHLY);
        pkg.setIsActive(isActive != null ? isActive : true);
        pkg.setPrice(BigDecimal.ZERO);
        packageRepository.save(pkg);
        return pkg.getId();
    }

    @Transactional
    public void updatePackageWithOptions(Long packageId, SubscriptionPackageWithOptionsRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new az.fitnest.order.exception.BadRequestException("error.missing_field");
        }
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));

        pkg.setName(request.name());
        pkg.setCurrency("AZN");
        pkg.setBillingPeriod(az.fitnest.order.model.enums.BillingPeriod.ONE_TIME);
        pkg.setIsActive(request.isActive() != null ? request.isActive() : pkg.getIsActive());
        pkg.setSortOrder(request.sortOrder() != null ? request.sortOrder() : pkg.getSortOrder());

        pkg.getOptions().clear();
        if (request.options() != null) {
            for (PackageOptionEntityDto dto : request.options()) {
                PackageOption opt = new PackageOption();
                opt.setSubscriptionPackage(pkg);
                opt.setDurationMonths(dto.durationMonths());
                opt.setPriceStandard(dto.priceStandard());
                opt.setPriceDiscounted(dto.priceDiscounted());
                opt.setEntryLimit(dto.entryLimit());
                opt.setFreezeDays(dto.freezeDays());
                pkg.getOptions().add(opt);
            }
        }

        if (!pkg.getOptions().isEmpty()) {
            PackageOption first = pkg.getOptions().iterator().next();
            if (first.getDurationMonths() != null && first.getDurationMonths() > 0 && first.getPriceStandard() != null) {
                BigDecimal monthly = first.getPriceStandard().divide(new BigDecimal(first.getDurationMonths()), 4, RoundingMode.HALF_UP);
                pkg.setPrice(monthly);
            }
        }

        packageRepository.save(pkg);
    }

    @Transactional
    public Long addOptionToPackage(Long packageId, az.fitnest.order.dto.PackageOptionEntityDto dto) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));

        boolean exists = pkg.getOptions().stream().anyMatch(opt ->
                Objects.equals(opt.getDurationMonths(), dto.durationMonths())
        );
        if (exists) {
            throw new az.fitnest.order.exception.BadRequestException("error.duration_already_exists");
        }

        PackageOption opt = new PackageOption();
        opt.setSubscriptionPackage(pkg);
        opt.setDurationMonths(dto.durationMonths());
        opt.setPriceStandard(dto.priceStandard());
        opt.setPriceDiscounted(dto.priceDiscounted());
        opt.setEntryLimit(dto.entryLimit());
        opt.setFreezeDays(dto.freezeDays());
        if (dto.benefits() != null) {
            java.util.List<az.fitnest.order.model.entity.PlanBenefit> benefits = new ArrayList<>();
            for (az.fitnest.order.model.entity.PlanBenefit pb : dto.benefits()) {
                benefits.add(pb);
            }
            opt.setBenefits(benefits);
        }
        pkg.getOptions().add(opt);
        packageRepository.save(pkg);
        return opt.getId();
    }

    @Transactional
    public void deletePackageById(Long packageId) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        packageRepository.delete(pkg);
    }

    @Transactional
    public void deleteAllPackages() {
        packageRepository.deleteAll();
    }

    @Transactional
    public void deleteOptionById(Long packageId, Long optionId) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        pkg.getOptions().removeIf(opt -> opt.getId().equals(optionId));
        packageRepository.save(pkg);
    }

    @Transactional
    public void deleteAllOptionsByPackageId(Long packageId) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        pkg.getOptions().clear();
        packageRepository.save(pkg);
    }
}
