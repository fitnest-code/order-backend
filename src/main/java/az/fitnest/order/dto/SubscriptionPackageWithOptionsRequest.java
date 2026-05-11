package az.fitnest.order.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record SubscriptionPackageWithOptionsRequest(
    String name,
    Boolean isActive,
    Integer sortOrder,
    Integer entryLimit,
    List<az.fitnest.order.model.entity.PlanBenefit> benefits,
    List<PackageOptionEntityDto> options
) {}
