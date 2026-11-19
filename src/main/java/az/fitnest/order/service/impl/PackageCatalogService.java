package az.fitnest.order.service.impl;

import az.fitnest.order.client.CatalogServiceGrpcClient;
import az.fitnest.order.dto.PackageBenefitDto;
import az.fitnest.order.dto.PackageListResponse;
import az.fitnest.order.dto.PackageNameDto;
import az.fitnest.order.dto.PackageOptionDto;
import az.fitnest.order.dto.PackagePlanListResponse;
import az.fitnest.order.dto.PackagePriceDto;
import az.fitnest.order.dto.SubscriptionPackageDto;
import az.fitnest.order.dto.SubscriptionPackageResponse;
import az.fitnest.order.dto.SubscriptionPackageSummaryV3;
import az.fitnest.order.dto.SubscriptionPackagesResponseV3;
import az.fitnest.order.exception.ResourceNotFoundException;
import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.PlanBenefit;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.pricingcampaign.dto.PricingDto;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.pricingcampaign.service.PricingCampaignService;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import az.fitnest.order.service.CampaignEligibilityService;
import az.fitnest.order.service.TranslationService;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PackageCatalogService {

    private static final Set<String> FEATURED_PACKAGE_NAMES = Set.of("bronze", "silver", "gold", "platinum");

    private final SubscriptionPackageRepository packageRepository;
    private final TranslationService translationService;
    private final CatalogServiceGrpcClient catalogServiceGrpcClient;
    private final az.fitnest.order.service.CampaignEligibilityService campaignEligibilityService;
    // Black Friday lives in the v4/checkout pricing stack; v1 /subscription-packages (used by mobile gym
    // detail) must show the same discounted price the user will actually be charged.
    private final PricingCampaignService pricingCampaignService;

    /** Legacy price fields plus the display extras derived from them, shared by option and package DTOs. */
    private record PriceView(PackagePriceDto price, String badge, BigDecimal strikeThroughTotal,
                             BigDecimal savingsAmount, BigDecimal monthlyEquivalent, boolean campaignApplied) {}

    /**
     * Resolved once per catalog call and passed to the mappers (avoids a campaign/eligibility query per option).
     * Anonymous callers get null on purpose: eligibility is per user and the anonymous response is cached
     * ("subscription-packages-public") without campaign state in its key, so it must stay campaign-free.
     */
    private PricingCampaign resolveCampaign() {
        Long userId = currentUserIdOrNull();
        if (userId == null) {
            return null;
        }
        return pricingCampaignService.applicableCampaign(userId).orElse(null);
    }

    private static Long currentUserIdOrNull() {
        try {
            return UserContext.getCurrentUserId();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public PackageListResponse getAllPackages(boolean activeOnly) {
        List<SubscriptionPackage> packages = activeOnly ?
                packageRepository.findByIsActiveTrueOrdered() :
                packageRepository.findAllOrdered();

        PricingCampaign campaign = resolveCampaign();
        List<SubscriptionPackageDto> dtos = new ArrayList<>();
        for (SubscriptionPackage pkg : packages) {
            if (pkg.getOptions() == null || pkg.getOptions().isEmpty()) {
                dtos.add(mapToDto(pkg, null, campaign));
            } else {
                for (PackageOption option : pkg.getOptions()) {
                    dtos.add(mapToDto(pkg, option, campaign));
                }
            }
        }

        return PackageListResponse.builder()
                .items(dtos)
                .build();
    }

    @Transactional(readOnly = true)
    @Cacheable(
            value = "subscription-packages-public",
            key = "{#order, T(az.fitnest.order.util.UserContext).getCurrentLanguage(), T(java.time.LocalDate).now(T(java.time.ZoneId).of('Asia/Baku'))}",
            // condition (not unless): `unless` only skips the PUT, so an authenticated user could still be
            // served an anonymous cached entry and miss their Black Friday prices.
            condition = "T(az.fitnest.order.util.UserContext).getCurrentUserId() == null"
    )
    public PackagePlanListResponse getUniquePlans(String order) {
        List<SubscriptionPackage> packages = packageRepository.findAllOrdered();

        boolean isDesc = "desc".equalsIgnoreCase(order);
        if (isDesc) {
            Collections.reverse(packages);
        }

        PricingCampaign campaign = resolveCampaign();
        List<SubscriptionPackageResponse> dtos = packages.stream()
                .map(p -> mapToPackageResponse(p, order, campaign))
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
    public SubscriptionPackagesResponseV3 getFeaturedPackages() {
        List<SubscriptionPackage> packages = packageRepository.findByIsActiveTrueOrdered().stream()
                .filter(pkg -> pkg.getName() != null
                        && FEATURED_PACKAGE_NAMES.contains(pkg.getName().trim().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());

        if (packages.isEmpty()) {
            throw new ResourceNotFoundException("error.plan_not_found");
        }

        String lang = UserContext.getCurrentLanguage();
        Map<Long, Long> gymCounts = catalogServiceGrpcClient.countGymsByPackages(
                packages.stream().map(SubscriptionPackage::getId).collect(Collectors.toList()));
        List<SubscriptionPackageSummaryV3> items = packages.stream()
                .map(pkg -> toPackageSummary(pkg, lang, gymCounts.getOrDefault(pkg.getId(), 0L)))
                .collect(Collectors.toList());

        return SubscriptionPackagesResponseV3.builder()
                .items(items)
                .build();
    }

    @Transactional(readOnly = true)
    public SubscriptionPackageResponse getPlanById(Long packageId) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        return mapToPackageResponse(pkg, "asc", resolveCampaign());
    }

    @Transactional(readOnly = true)
    public List<PackageOptionDto> getOptionsByPlanId(Long packageId) {
        SubscriptionPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found"));
        PricingCampaign campaign = resolveCampaign();
        return pkg.getOptions().stream()
                .map(o -> mapToOptionDto(pkg, o, campaign))
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

        return mapToDto(pkg, option, resolveCampaign());
    }

    @Transactional(readOnly = true)
    public SubscriptionPackageDto getPackageByOptionId(Long optionId) {
        PricingCampaign campaign = resolveCampaign();
        for (SubscriptionPackage pkg : packageRepository.findAllOrdered()) {
            for (PackageOption option : pkg.getOptions()) {
                if (option.getId().equals(optionId)) {
                    return mapToDto(pkg, option, campaign);
                }
            }
        }
        throw new az.fitnest.order.exception.ResourceNotFoundException("error.plan_not_found");
    }

    private SubscriptionPackageResponse mapToPackageResponse(SubscriptionPackage pkg, String order, PricingCampaign campaign) {
        String lang = UserContext.getCurrentLanguage();
        String localizedName = translationService.getTranslatedValue("SUBSCRIPTIONPACKAGE", pkg.getId().toString(), "name", lang);
        if (localizedName == null || localizedName.isEmpty()) localizedName = pkg.getName();

        boolean isDesc = "desc".equalsIgnoreCase(order);

        List<PackageOptionDto> options = pkg.getOptions().stream()
                .map(o -> mapToOptionDto(pkg, o, campaign))
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

    /**
     * Price math for one option. With an applicable Black Friday campaign the price comes from
     * {@link PricingCampaignService#computePricing} (list = priceStandard, final = campaign price) and the
     * legacy {@code priceDiscounted} is ignored, same rule as v4 and checkout; otherwise legacy behavior.
     */
    private PriceView priceView(SubscriptionPackage pkg, PackageOption option, PricingCampaign campaign) {
        BigDecimal base = option.getPriceStandard();
        Integer months = option.getDurationMonths();

        if (campaign != null && base != null && months != null && months > 0) {
            PricingDto pricing = pricingCampaignService.computePricing(base, months, campaign);
            if (pricing.campaignApplied()) {
                BigDecimal fin = pricing.finalPrice();
                return new PriceView(
                        PackagePriceDto.builder()
                                .base(pricing.listPrice())
                                .discount(fin)
                                .effective(fin)
                                .currency(pkg.getCurrency())
                                .build(),
                        "discount",
                        pricing.listPrice(),
                        pricing.savedAmount(),
                        pricing.monthlyPrice(),
                        true);
            }
        }

        BigDecimal discount = option.getPriceDiscounted();
        BigDecimal effective = discount != null ? discount : base;
        boolean discounted = discount != null && base != null && discount.compareTo(base) < 0;
        BigDecimal savings = (discounted && base.subtract(discount).signum() > 0) ? base.subtract(discount) : null;
        BigDecimal monthly = (effective != null && months != null && months > 0)
                ? effective.divide(BigDecimal.valueOf(months), 2, java.math.RoundingMode.HALF_UP)
                : null;
        return new PriceView(
                PackagePriceDto.builder()
                        .base(base)
                        .discount(discount)
                        .effective(effective)
                        .currency(pkg.getCurrency())
                        .build(),
                discounted ? "discount" : null,
                discounted ? base : null,
                savings,
                monthly,
                false);
    }

    private PackageOptionDto mapToOptionDto(SubscriptionPackage pkg, PackageOption option, PricingCampaign campaign) {
        String lang = UserContext.getCurrentLanguage();
        PriceView view = priceView(pkg, option, campaign);

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

        Long userId = currentUserIdOrNull();

        az.fitnest.order.dto.BonusDecision decision = campaignEligibilityService.resolve(
                userId, pkg.getId(), option.getDurationMonths(), az.fitnest.order.dto.PurchaseKind.NEW_PURCHASE, null);

        return PackageOptionDto.builder()
                .optionId(option.getId())
                .durationMonths(option.getDurationMonths())
                .durationLabel(getDurationLabel(option.getDurationMonths(), lang))
                .price(view.price())
                .badge(decision.isApplied() ? "campaign" : view.badge())
                .visitLimit(option.getEntryLimit() != null ? option.getEntryLimit() : (pkg.getEntryLimit() != null ? pkg.getEntryLimit() : 0))
                .freezeDays(0)
                .benefits(benefits)
                .bonusMonths(decision.getBonusMonths())
                .totalMonths(decision.getTotalMonths())
                .campaignId(decision.getCampaignId())
                .campaignLabel(decision.getCampaignLabel())
                .strikeThroughTotal(view.strikeThroughTotal())
                .savingsAmount(view.savingsAmount())
                .monthlyEquivalent(view.monthlyEquivalent())
                .build();
    }

    private SubscriptionPackageDto mapToDto(SubscriptionPackage pkg, PackageOption option, PricingCampaign campaign) {
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
            PriceView view = priceView(pkg, option, campaign);
            priceDto = view.price();
            badge = view.badge();

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

    private SubscriptionPackageSummaryV3 toPackageSummary(SubscriptionPackage pkg, String lang, long gymCount) {
        String localizedName = translationService.getTranslatedValue(
                "SUBSCRIPTIONPACKAGE", pkg.getId().toString(), "name", lang);
        if (localizedName == null || localizedName.isEmpty()) {
            localizedName = pkg.getName();
        }

        return SubscriptionPackageSummaryV3.builder()
                .subscriptionName(localizedName)
                .gymCount(gymCount)
                .monthlyPrice(resolveMonthlyPrice(pkg))
                .services(uniqueServicesFromPackage(pkg, lang))
                .build();
    }

    private List<String> uniqueServicesFromPackage(SubscriptionPackage pkg, String lang) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> services = new ArrayList<>();
        if (pkg.getBenefits() == null) {
            return services;
        }
        for (PlanBenefit benefit : pkg.getBenefits()) {
            String description = localizeBenefit(pkg, benefit, lang);
            if (description == null || description.isBlank()) {
                continue;
            }
            String trimmed = description.trim();
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                services.add(trimmed);
            }
        }
        return services;
    }

    private String localizeBenefit(SubscriptionPackage pkg, PlanBenefit benefit, String lang) {
        if (benefit == null || benefit.getDescription() == null) {
            return null;
        }
        String entityId = pkg.getId() + "_" + benefit.getDescription();
        String localized = translationService.getTranslatedValue("PLANBENEFIT", entityId, "description", lang);
        return (localized != null && !localized.isEmpty()) ? localized : benefit.getDescription();
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
