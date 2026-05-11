package az.fitnest.order.service.impl;

import az.fitnest.order.dto.*;
import az.fitnest.order.model.entity.*;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.util.UserContext;
import az.fitnest.order.service.TranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class PackageCatalogService {

    private final SubscriptionPackageRepository packageRepository;
    private final TranslationService translationService;

    @Transactional(readOnly = true)
    public PackageListResponse getAllPackages(boolean activeOnly) {
        List<SubscriptionPackage> packages = activeOnly ?
                packageRepository.findByIsActiveTrue() :
                packageRepository.findAll();

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
    public PackagePlanListResponse getUniquePlans(String order) {
        List<SubscriptionPackage> packages = packageRepository.findAll();

        List<String> orderList = List.of("bronze", "silver", "gold", "platinum");
        boolean isDesc = "desc".equalsIgnoreCase(order);

        packages.sort((p1, p2) -> {
            int i1 = orderList.indexOf(p1.getName().toLowerCase());
            int i2 = orderList.indexOf(p2.getName().toLowerCase());

            if (i1 == -1) i1 = orderList.size();
            if (i2 == -1) i2 = orderList.size();

            int cmp = Integer.compare(i1, i2);
            return isDesc ? -cmp : cmp;
        });

        List<SubscriptionPackageResponse> dtos = packages.stream()
                .map(p -> mapToPackageResponse(p, order))
                .collect(Collectors.toList());

        return PackagePlanListResponse.builder()
                .items(dtos)
                .build();
    }

    @Transactional(readOnly = true)
    public List<PackageNameDto> getPackageNames() {
        return packageRepository.findAll().stream()
                .map(pkg -> PackageNameDto.builder()
                        .id(pkg.getId())
                        .name(pkg.getName())
                        .build())
                .collect(Collectors.toList());
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
        for (SubscriptionPackage pkg : packageRepository.findAll()) {
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
                .visitLimit(pkg.getEntryLimit() != null ? pkg.getEntryLimit() : 0)
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

            visitLimit = pkg.getEntryLimit() != null ? pkg.getEntryLimit() : 0;
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

    private String getDurationLabel(Integer months, String lang) {
        if (months == null) return null;
        String label = translationService.getTranslatedValue("DURATION", months.toString(), "label", lang);
        if (label == null || label.isEmpty()) {
            return months + " ay";
        }
        return label;
    }
}
