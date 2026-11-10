package az.fitnest.order.pricingcampaign.service;

import az.fitnest.order.model.entity.PackageOption;
import az.fitnest.order.model.entity.SubscriptionPackage;
import az.fitnest.order.pricingcampaign.dto.PackageOptionV4Dto;
import az.fitnest.order.pricingcampaign.dto.PackageV4Dto;
import az.fitnest.order.pricingcampaign.entity.PricingCampaign;
import az.fitnest.order.repository.SubscriptionPackageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** v4 catalog: same packages as v3, but every option carries a campaign-aware {@code pricing} block. */
@Service
@RequiredArgsConstructor
public class PricingPackageCatalogV4Service {

    private final SubscriptionPackageRepository packageRepository;
    private final PricingCampaignService campaignService;

    @Transactional(readOnly = true)
    public List<PackageV4Dto> getPackages(Long userId) {
        // Resolve once: the same campaign applies to every option on this response.
        PricingCampaign campaign = campaignService.applicableCampaign(userId).orElse(null);
        return packageRepository.findByIsActiveTrueOrdered().stream()
                .map(p -> toDto(p, campaign))
                .toList();
    }

    private PackageV4Dto toDto(SubscriptionPackage p, PricingCampaign campaign) {
        List<PackageOptionV4Dto> options = p.getOptions().stream()
                .filter(o -> !Boolean.FALSE.equals(o.getIsActive()) && o.getPriceStandard() != null
                        && o.getDurationMonths() != null && o.getDurationMonths() > 0)
                .sorted(Comparator.comparing(PackageOption::getDurationMonths))
                // List price is priceStandard only; priceDiscounted is a legacy field Black Friday ignores.
                .map(o -> new PackageOptionV4Dto(o.getId(), o.getDurationMonths(),
                        campaignService.computePricing(o.getPriceStandard(), o.getDurationMonths(), campaign)))
                .toList();
        return new PackageV4Dto(p.getId(), p.getName(), p.getCurrency(), p.getSortOrder(), options);
    }
}
