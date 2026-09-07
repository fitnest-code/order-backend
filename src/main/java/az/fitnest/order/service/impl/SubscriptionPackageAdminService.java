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
@Transactional(readOnly = true)
public class SubscriptionPackageAdminService {
    @Transactional(readOnly = true)
    @org.springframework.cache.annotation.Cacheable(value = "subscription-packages-paged", key = "{#page, #size, T(org.springframework.context.i18n.LocaleContextHolder).getLocale().getLanguage()}")
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

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private void cleanupStaleOptionBenefitsTable() {
        try {
            entityManager.createNativeQuery("DROP TABLE IF EXISTS membership_plan_option_benefits CASCADE").executeUpdate();
        } catch (Exception e) {
            // Ignore if table does not exist or permission denied
        }
    }

    private final SubscriptionPackageRepository packageRepository;
    private final az.fitnest.order.service.TranslationService translationService;

    @Transactional(readOnly = true)
    @org.springframework.cache.annotation.Cacheable(value = "subscription-packages-all", key = "T(org.springframework.context.i18n.LocaleContextHolder).getLocale().getLanguage()")
    public List<az.fitnest.order.dto.AdminSubscriptionPackageResponse> getAllPackages() {
        return packageRepository.findAllWithOptions().stream()
                .map(this::toAdminPackageResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    @org.springframework.cache.annotation.Cacheable(value = "subscription-package-options", key = "T(org.springframework.context.i18n.LocaleContextHolder).getLocale().getLanguage()")
    public List<az.fitnest.order.dto.AdminPackageOptionDetailResponse> getAllPackageOptions() {
        String lang = az.fitnest.order.util.UserContext.getCurrentLanguage();
        return packageRepository.findAllWithOptions().stream()
                .flatMap(pkg -> pkg.getOptions().stream()
                        .map(opt -> {
                            List<String> localizedBenefits = pkg.getBenefits() != null ?
                                    pkg.getBenefits().stream()
                                            .map(b -> {
                                                String entityId = pkg.getId() + "_" + b.getDescription();
                                                String localizedBenefit = translationService.getTranslatedValue("PLANBENEFIT", entityId, "description", lang);
                                                return localizedBenefit != null && !localizedBenefit.isEmpty() ? localizedBenefit : b.getDescription();
                                            })
                                            .toList() :
                                    List.of();
                            return az.fitnest.order.dto.AdminPackageOptionDetailResponse.builder()
                                    .packageId(pkg.getId())
                                    .packageName(pkg.getName())
                                    .optionId(opt.getId())
                                    .durationMonths(opt.getDurationMonths())
                                    .priceStandard(opt.getPriceStandard())
                                    .priceDiscounted(opt.getPriceDiscounted())
                                    .entryLimit(opt.getEntryLimit() != null ? opt.getEntryLimit() : pkg.getEntryLimit())
                                    .isActive(opt.getIsActive() != null ? opt.getIsActive() : true)
                                    .benefits(localizedBenefits)
                                    .build();
                        }))
                .toList();
    }

    @Transactional(readOnly = true)
    @org.springframework.cache.annotation.Cacheable(value = "subscription-package-names")
    public List<az.fitnest.order.dto.PackageNameDto> getPackageNames() {
        return packageRepository.findAll().stream()
                .map(pkg -> az.fitnest.order.dto.PackageNameDto.builder()
                        .id(pkg.getId())
                        .name(pkg.getName())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<az.fitnest.order.dto.PackageIdAndOptionIdsResponse> getPackageAndOptionIds() {
        return packageRepository.findAllWithOptions().stream()
                .map(pkg -> az.fitnest.order.dto.PackageIdAndOptionIdsResponse.builder()
                        .packageId(pkg.getId())
                        .optionIds(pkg.getOptions() != null ? 
                                pkg.getOptions().stream().map(az.fitnest.order.model.entity.PackageOption::getId).toList() : 
                                java.util.List.of())
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
        String lang = az.fitnest.order.util.UserContext.getCurrentLanguage();
        List<az.fitnest.order.dto.AdminSubscriptionPackageResponse.AdminPackageOptionResponse> options =
                pkg.getOptions().stream()
                        .map(opt -> az.fitnest.order.dto.AdminSubscriptionPackageResponse.AdminPackageOptionResponse.builder()
                                .optionId(opt.getId())
                                .durationMonths(opt.getDurationMonths())
                                .priceStandard(opt.getPriceStandard())
                                .priceDiscounted(opt.getPriceDiscounted())
                                .isActive(opt.getIsActive() != null ? opt.getIsActive() : true)
                                .entryLimit(opt.getEntryLimit())
                                .build())
                        .toList();

        List<String> localizedBenefits = pkg.getBenefits() != null ?
                pkg.getBenefits().stream()
                        .map(b -> {
                            String entityId = pkg.getId() + "_" + b.getDescription();
                            String localizedBenefit = translationService.getTranslatedValue("PLANBENEFIT", entityId, "description", lang);
                            return localizedBenefit != null && !localizedBenefit.isEmpty() ? localizedBenefit : b.getDescription();
                        })
                        .toList() :
                List.of();

        return az.fitnest.order.dto.AdminSubscriptionPackageResponse.builder()
                .packageId(pkg.getId())
                .name(pkg.getName())
                .isActive(pkg.getIsActive() != null ? pkg.getIsActive() : true)
                .sortOrder(pkg.getSortOrder())
                .entryLimit(pkg.getEntryLimit())
                .benefits(localizedBenefits)
                .durationOptions(options)
                .build();
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
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

        if (pkg.getId() == null) {
            pkg = packageRepository.save(pkg);
        }

        pkg.setEntryLimit(request.entryLimit());
        pkg.getBenefits().clear();
        if (request.benefits() != null) {
            java.util.Set<String> uniqueDescriptions = new java.util.HashSet<>();
            for (az.fitnest.order.model.entity.PlanBenefit b : request.benefits()) {
                if (b != null && b.getDescription() != null && !b.getDescription().isBlank()) {
                    String trimmed = b.getDescription().trim();
                    if (uniqueDescriptions.add(trimmed.toLowerCase())) {
                        pkg.getBenefits().add(new az.fitnest.order.model.entity.PlanBenefit(trimmed));
                    }
                }
            }
        }

        if (request.options() != null) {
            java.util.Set<Integer> seenDurations = new java.util.HashSet<>();
            java.util.Map<Integer, PackageOption> existingOptionsMap = new java.util.HashMap<>();
            if (pkg.getOptions() != null) {
                pkg.getOptions().forEach(o -> existingOptionsMap.put(o.getDurationMonths(), o));
            }

            java.util.Set<PackageOption> updatedOptions = new java.util.HashSet<>();
            for (PackageOptionEntityDto dto : request.options()) {
                if (!seenDurations.add(dto.durationMonths())) {
                    throw new az.fitnest.order.exception.BadRequestException("error.duration_already_exists");
                }
                if (dto.priceDiscounted() != null && dto.priceStandard() != null &&
                    dto.priceDiscounted().compareTo(dto.priceStandard()) > 0) {
                    throw new az.fitnest.order.exception.BadRequestException("error.discount_higher_than_price");
                }
                
                PackageOption opt = existingOptionsMap.getOrDefault(dto.durationMonths(), new PackageOption());
                opt.setSubscriptionPackage(pkg);
                opt.setDurationMonths(dto.durationMonths());
                opt.setPriceStandard(dto.priceStandard());
                opt.setPriceDiscounted(dto.priceDiscounted());
                opt.setIsActive(dto.isActive() != null ? dto.isActive() : true);
                opt.setEntryLimit(dto.entryLimit());
                updatedOptions.add(opt);
            }
            
            if (pkg.getOptions() == null) {
                pkg.setOptions(updatedOptions);
            } else {
                pkg.getOptions().clear();
                pkg.getOptions().addAll(updatedOptions);
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
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public Long createPackage(String name, String currency, BillingPeriod billingPeriod, Boolean isActive) {
        List<String> allowedNames = List.of("Bronze", "Silver", "Gold", "Platinum");
        if (name == null || name.isBlank() || !allowedNames.contains(name)) {
            throw new az.fitnest.order.exception.BadRequestException("error.invalid_package_name");
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
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void updatePackageWithOptions(Long packageId, SubscriptionPackageWithOptionsRequest request) {
        cleanupStaleOptionBenefitsTable();
        List<String> allowedNames = List.of("Bronze", "Silver", "Gold", "Platinum");
        if (request.name() == null || request.name().isBlank() || !allowedNames.contains(request.name())) {
            throw new az.fitnest.order.exception.BadRequestException("error.invalid_package_name");
        }
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));

        pkg.setName(request.name());
        pkg.setCurrency("AZN");
        pkg.setBillingPeriod(az.fitnest.order.model.enums.BillingPeriod.ONE_TIME);
        pkg.setIsActive(request.isActive() != null ? request.isActive() : pkg.getIsActive());
        pkg.setSortOrder(request.sortOrder() != null ? request.sortOrder() : pkg.getSortOrder());
        pkg.setEntryLimit(request.entryLimit());
        
        pkg.getBenefits().clear();
        if (request.benefits() != null) {
            java.util.Set<String> uniqueDescriptions = new java.util.HashSet<>();
            for (az.fitnest.order.model.entity.PlanBenefit b : request.benefits()) {
                if (b != null && b.getDescription() != null && !b.getDescription().isBlank()) {
                    String trimmed = b.getDescription().trim();
                    if (uniqueDescriptions.add(trimmed.toLowerCase())) {
                        pkg.getBenefits().add(new az.fitnest.order.model.entity.PlanBenefit(trimmed));
                    }
                }
            }
        }

        if (request.options() != null) {
            java.util.Set<Integer> seenDurations = new java.util.HashSet<>();
            java.util.Map<Integer, PackageOption> existingOptionsMap = new java.util.HashMap<>();
            if (pkg.getOptions() != null) {
                pkg.getOptions().forEach(o -> existingOptionsMap.put(o.getDurationMonths(), o));
            }

            java.util.Set<PackageOption> updatedOptions = new java.util.HashSet<>();
            for (PackageOptionEntityDto dto : request.options()) {
                if (!seenDurations.add(dto.durationMonths())) {
                    throw new az.fitnest.order.exception.BadRequestException("error.duration_already_exists");
                }
                if (dto.priceDiscounted() != null && dto.priceStandard() != null &&
                    dto.priceDiscounted().compareTo(dto.priceStandard()) > 0) {
                    throw new az.fitnest.order.exception.BadRequestException("error.discount_higher_than_price");
                }
                
                PackageOption opt = existingOptionsMap.getOrDefault(dto.durationMonths(), new PackageOption());
                opt.setSubscriptionPackage(pkg);
                opt.setDurationMonths(dto.durationMonths());
                opt.setPriceStandard(dto.priceStandard());
                opt.setPriceDiscounted(dto.priceDiscounted());
                opt.setIsActive(dto.isActive() != null ? dto.isActive() : true);
                opt.setEntryLimit(dto.entryLimit());
                updatedOptions.add(opt);
            }
            
            pkg.getOptions().clear();
            pkg.getOptions().addAll(updatedOptions);
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
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public Long addOptionToPackage(Long packageId, az.fitnest.order.dto.PackageOptionEntityDto dto) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));

        boolean exists = pkg.getOptions().stream().anyMatch(opt ->
                Objects.equals(opt.getDurationMonths(), dto.durationMonths())
        );
        if (exists) {
            throw new az.fitnest.order.exception.BadRequestException("error.duration_already_exists");
        }
        if (dto.priceDiscounted() != null && dto.priceStandard() != null &&
            dto.priceDiscounted().compareTo(dto.priceStandard()) > 0) {
            throw new az.fitnest.order.exception.BadRequestException("error.discount_higher_than_price");
        }

        PackageOption opt = new PackageOption();
        opt.setSubscriptionPackage(pkg);
        opt.setDurationMonths(dto.durationMonths());
        opt.setPriceStandard(dto.priceStandard());
        opt.setPriceDiscounted(dto.priceDiscounted());
        opt.setIsActive(dto.isActive() != null ? dto.isActive() : true);
        opt.setEntryLimit(dto.entryLimit());
        pkg.getOptions().add(opt);
        packageRepository.save(pkg);
        return opt.getId();
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void deletePackageById(Long packageId) {
        cleanupStaleOptionBenefitsTable();
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        packageRepository.delete(pkg);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void deleteAllPackages() {
        cleanupStaleOptionBenefitsTable();
        packageRepository.deleteAll();
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void deleteOptionById(Long packageId, Long optionId) {
        cleanupStaleOptionBenefitsTable();
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        pkg.getOptions().removeIf(opt -> opt.getId().equals(optionId));
        packageRepository.save(pkg);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void deleteAllOptionsByPackageId(Long packageId) {
        cleanupStaleOptionBenefitsTable();
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        pkg.getOptions().clear();
        packageRepository.save(pkg);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void updateOptionStatus(Long packageId, Long optionId, boolean isActive) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        PackageOption option = pkg.getOptions().stream()
                .filter(opt -> opt.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.option_not_found"));
        option.setIsActive(isActive);
        packageRepository.save(pkg);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void updatePackageStatus(Long packageId, boolean isActive) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        pkg.setIsActive(isActive);
        packageRepository.save(pkg);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void updateOption(Long packageId, Long optionId, az.fitnest.order.dto.PackageOptionEntityDto dto) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        PackageOption opt = pkg.getOptions().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.option_not_found"));

        opt.setDurationMonths(dto.durationMonths());
        if (dto.priceDiscounted() != null && dto.priceStandard() != null &&
            dto.priceDiscounted().compareTo(dto.priceStandard()) > 0) {
            throw new az.fitnest.order.exception.BadRequestException("error.discount_higher_than_price");
        }
        opt.setPriceStandard(dto.priceStandard());
        opt.setPriceDiscounted(dto.priceDiscounted());
        opt.setIsActive(dto.isActive() != null ? dto.isActive() : opt.getIsActive());
        opt.setEntryLimit(dto.entryLimit());
        packageRepository.save(pkg);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void addBenefit(Long packageId, String description) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        if (description != null && !description.isBlank()) {
            String trimmed = description.trim();
            boolean exists = pkg.getBenefits().stream()
                    .anyMatch(b -> b.getDescription().equalsIgnoreCase(trimmed));
            if (!exists) {
                pkg.getBenefits().add(new az.fitnest.order.model.entity.PlanBenefit(trimmed));
                packageRepository.save(pkg);
            }
        }
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"subscription-packages-paged", "subscription-packages-all", "subscription-package-options", "subscription-package-names", "subscription-packages-public"}, allEntries = true)
    public void deleteBenefit(Long packageId, String description) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        pkg.getBenefits().removeIf(b -> b.getDescription().equals(description));
        packageRepository.save(pkg);
    }
}
