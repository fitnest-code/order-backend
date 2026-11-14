package az.fitnest.order.pricingcampaign.controller;

import az.fitnest.order.pricingcampaign.dto.PackageV4Dto;
import az.fitnest.order.pricingcampaign.service.PricingPackageCatalogV4Service;
import az.fitnest.order.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v4/subscription-packages")
@RequiredArgsConstructor
public class PackageCatalogV4Controller {

    private final PricingPackageCatalogV4Service service;

    @GetMapping
    public List<PackageV4Dto> getPackages() {
        return service.getPackages(UserContext.getCurrentUserId());
    }
}
