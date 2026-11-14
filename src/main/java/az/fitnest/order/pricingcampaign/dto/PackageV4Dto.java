package az.fitnest.order.pricingcampaign.dto;

import java.util.List;

public record PackageV4Dto(Long id, String name, String currency, Integer sortOrder, List<PackageOptionV4Dto> options) {}
