package az.fitnest.order.service.impl;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.dto.PackageBenefitDto;
import az.fitnest.order.dto.PackageListResponse;
import az.fitnest.order.dto.PackageNameDto;
import az.fitnest.order.dto.PackageOptionDto;
import az.fitnest.order.dto.PackagePlanListResponse;
import az.fitnest.order.dto.PackagePriceDto;
import az.fitnest.order.dto.RandomSubscriptionPackageResponse;
import az.fitnest.order.dto.SubscriptionPackageDto;
import az.fitnest.order.dto.SubscriptionPackageResponse;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.service.TranslationService;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PackageCatalogService {

    private static final Set<String> FEATURED_PACKAGE_NAMES = Set.of("bronze", "silver", "gold", "platinum");

    private final SubscriptionPackageRepository packageRepository;
    private final TranslationService translationService;
    private final CatalogServiceGrpcClient catalogServiceGrpcClient;

    @Transactional(readOnly = true)
    public PackageListResponse getAllPackages(boolean activeOnly) {
        List<SubscriptionPackage> packages = activeOnly ?
                packageRepository.findByIsActiveTrueOrdered() :
                packageRepository.findAllOrdered();

        List<SubscriptionPackageDto> dtos = new ArrayList<>();
        for (SubscriptionPackage pkg : packages) {
            if (pkg.getOptions() == null || pkg.getOptions().isEmpty()) {
                dtos.add(mapToDto(pkg, null));
            } else {
                for (PackageOption option : pkg.getOptions()) {
                    dtos.add(mapToDto(pkg, option));
                }
            }
        }

        return PackageListResponse.builder()
                .items(dtos)
                .build();
    }

    @Transactional(readOnly = true)
    @org.springframework.cache.annotation.Cacheable(
            value = "subscription-packages-public",
            key = "{#order, T(az.fitnest.order.util.UserContext).getCurrentLanguage()}"
    )
    public PackagePlanListResponse getUniquePlans(String order) {
        List<SubscriptionPackage> packages = packageRepository.findAllOrdered();

        boolean isDesc = "desc".equalsIgnoreCase(order);
        if (isDesc) {
            Collections.reverse(packages);
        }

        List<SubscriptionPackageResponse> dtos = packages.stream()
                .map(p -> mapToPackageResponse(p, order))
                .collect(Collectors.toList());

        return PackagePlanListResponse.builder()
                .items(dtos)
                .build();
    }

    @Transactional(readOnly = true)
    public List<PackageNameDto> getPackageNames() {
        return packageRepository.findAllOrdered().stream()
                .map(pkg -> PackageNameDto.builder()
                        .id(pkg.getId())
                        .name(pkg.getName())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RandomSubscriptionPackageResponse getRandomFeaturedPackage() {
        List<SubscriptionPackage> packages = packageRepository.findByIsActiveTrueOrdered().stream()
                .filter(pkg -> pkg.getName() != null
                        && FEATURED_PACKAGE_NAMES.contains(pkg.getName().trim().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());

        if (packages.isEmpty()) {
            throw new ResourceNotFoundException("error.plan_not_found");
        }

        SubscriptionPackage pkg = packages.get(ThreadLocalRandom.current().nextInt(packages.size()));
        String lang = UserContext.getCurrentLanguage();
        String localizedName = translationService.getTranslatedValue(
                "SUBSCRIPTIONPACKAGE", pkg.getId().toString(), "name", lang);
        if (localizedName == null || localizedName.isEmpty()) {
            localizedName = pkg.getName();
        }

        List<String> services = pkg.getBenefits() == null
                ? List.of()
                : pkg.getBenefits().stream()
                        .map(benefit -> {
                            String entityId = pkg.getId() + "_" + benefit.getDescription();
                            String localized = translationService.getTranslatedValue(
                                    "PLANBENEFIT", entityId, "description", lang);
                            return (localized != null && !localized.isEmpty())
                                    ? localized
                                    : benefit.getDescription();
                        })
                        .filter(description -> description != null && !description.isBlank())
                        .collect(Collectors.toList());

        return RandomSubscriptionPackageResponse.builder()
                .subscriptionName(localizedName)
                .gymCount(catalogServiceGrpcClient.countGymsByPackage(pkg.getId()))
                .monthlyPrice(resolveMonthlyPrice(pkg))
                .services(services)
                .build();
    }

    @Transactional(readOnly = true)
    public SubscriptionPackageResponse getPlanById(Long packageId) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        return mapToPackageResponse(pkg, "asc");
    }

    @Transactional(readOnly = true)
    public List<PackageOptionDto> getOptionsByPlanId(Long packageId) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        return pkg.getOptions().stream()
                .map(o -> mapToOptionDto(pkg, o))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SubscriptionPackageDto getOptionDetails(Long packageId, Long optionId) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));

        PackageOption option = pkg.getOptions().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.option_not_found"));

        return mapToDto(pkg, option);
    }

    @Transactional(readOnly = true)
    public SubscriptionPackageDto getPackageByOptionId(Long optionId) {
        for (SubscriptionPackage pkg : packageRepository.findAllOrdered()) {
            for (PackageOption option : pkg.getOptions()) {
                if (option.getId().equals(optionId)) {
                    return mapToDto(pkg, option);
                }
            }
        }
        throw new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found");
    }

    private SubscriptionPackageResponse mapToPackageResponse(SubscriptionPackage pkg, String order) {
        String lang = UserContext.getCurrentLanguage();
        String localizedName = translationService.getTranslatedValue("SUBSCRIPTIONPACKAGE", pkg.getId().toString(), "name", lang);
        if (localizedName == null || localizedName.isEmpty()) localizedName = pkg.getName();

        boolean isDesc = "desc".equalsIgnoreCase(order);

        List<PackageOptionDto> options = pkg.getOptions().stream()
                .map(o -> mapToOptionDto(pkg, o))
                .sorted((o1, o2) -> {
                    int cmp = Integer.compare(o1.durationMonths(), o2.durationMonths());
                    return isDesc ? -cmp : cmp;
                })
                .collect(Collectors.toList());

        return SubscriptionPackageResponse.builder()
                .packageId(pkg.getId().toString())
                .name(localizedName)
                .isActive(pkg.getIsActive())
                .options(options)
                .build();
    }

    private PackageOptionDto mapToOptionDto(SubscriptionPackage pkg, PackageOption option) {
        String lang = UserContext.getCurrentLanguage();
        BigDecimal base = option.getPriceStandard();
        BigDecimal discount = option.getPriceDiscounted();
        BigDecimal effective = discount != null ? discount : base;

        PackagePriceDto priceDto = PackagePriceDto.builder()
                .base(base)
                .discount(discount)
                .effective(effective)
                .currency(pkg.getCurrency())
                .build();

        String badge = (discount != null && base != null && discount.compareTo(base) < 0) ? "discount" : null;

        List<PackageBenefitDto> benefits = pkg.getBenefits() != null ?
                pkg.getBenefits().stream()
                        .map(b -> {
                            String entityId = pkg.getId() + "_" + b.getDescription();
                            String localizedBenefit = translationService.getTranslatedValue("PLANBENEFIT", entityId, "description", lang);
                            return PackageBenefitDto.builder()
                                    .description(localizedBenefit != null ? localizedBenefit : b.getDescription())
                                    .build();
                        })
                        .collect(Collectors.toList()) :
                List.of();

        return PackageOptionDto.builder()
                .optionId(option.getId())
                .durationMonths(option.getDurationMonths())
                .durationLabel(getDurationLabel(option.getDurationMonths(), lang))
                .price(priceDto)
                .badge(badge)
                .visitLimit(option.getEntryLimit() != null ? option.getEntryLimit() : (pkg.getEntryLimit() != null ? pkg.getEntryLimit() : 0))
                .freezeDays(0)
                .benefits(benefits)
                .build();
    }

    private SubscriptionPackageDto mapToDto(SubscriptionPackage pkg, PackageOption option) {
        String lang = UserContext.getCurrentLanguage();
        String localizedPkgName = translationService.getTranslatedValue("SUBSCRIPTIONPACKAGE", pkg.getId().toString(), "name", lang);
        if (localizedPkgName == null || localizedPkgName.isEmpty()) localizedPkgName = pkg.getName();

        PackagePriceDto priceDto = null;
        String badge = null;
        Integer visitLimit = 0;
        Integer freezeDays = 0;
        Integer durationMonths = null;
        String durationLabel = null;
        Long optionId = null;
        List<PackageBenefitDto> benefits = List.of();

        if (option != null) {
            optionId = option.getId();
            durationMonths = option.getDurationMonths();
            durationLabel = getDurationLabel(durationMonths, lang);
            BigDecimal base = option.getPriceStandard();
            BigDecimal discount = option.getPriceDiscounted();
            BigDecimal effective = discount != null ? discount : base;

            priceDto = PackagePriceDto.builder()
                    .base(base)
                    .discount(discount)
                    .effective(effective)
                    .currency(pkg.getCurrency())
                    .build();

            if (discount != null && base != null && discount.compareTo(base) < 0) {
                badge = "discount";
            }

            visitLimit = option.getEntryLimit() != null ? option.getEntryLimit() : (pkg.getEntryLimit() != null ? pkg.getEntryLimit() : 0);
            freezeDays = 0;

            if (pkg.getBenefits() != null) {
                benefits = pkg.getBenefits().stream()
                        .map(b -> {
                            String entityId = pkg.getId() + "_" + b.getDescription();
                            String localizedBenefit = translationService.getTranslatedValue("PLANBENEFIT", entityId, "description", lang);
                            return PackageBenefitDto.builder()
                                    .description(localizedBenefit != null ? localizedBenefit : b.getDescription())
                                    .build();
                        })
                        .collect(Collectors.toList());
            }
        }

        return SubscriptionPackageDto.builder()
                .packageId(pkg.getId().toString())
                .optionId(optionId)
                .name(localizedPkgName)
                .durationMonths(durationMonths)
                .durationLabel(durationLabel)
                .isActive(pkg.getIsActive())
                .price(priceDto)
                .badge(badge)
                .visitLimit(visitLimit)
                .freezeDays(freezeDays)
                .benefits(benefits)
                .build();
    }

    private BigDecimal resolveMonthlyPrice(SubscriptionPackage pkg) {
        if (pkg.getOptions() != null && !pkg.getOptions().isEmpty()) {
            return pkg.getOptions().stream()
                    .filter(option -> !Boolean.FALSE.equals(option.getIsActive()))
                    .filter(option -> option.getDurationMonths() != null && option.getDurationMonths() > 0)
                    .min(Comparator.comparingInt(PackageOption::getDurationMonths))
                    .map(this::effectiveOptionPrice)
                    .orElse(pkg.getPrice());
        }
        return pkg.getPrice();
    }

    private BigDecimal effectiveOptionPrice(PackageOption option) {
        if (option.getPriceDiscounted() != null) {
            return option.getPriceDiscounted();
        }
        return option.getPriceStandard();
    }

    private String getDurationLabel(Integer months, String lang) {
        if (months == null) return null;
        String label = translationService.getTranslatedValue("DURATION", months.toString(), "label", lang);
        if (label == null || label.isEmpty()) {
            return months + " ay";
        }
        return label;
    }
}
