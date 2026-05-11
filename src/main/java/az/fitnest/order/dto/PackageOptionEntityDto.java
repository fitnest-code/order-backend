package az.fitnest.order.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

@Builder
public record PackageOptionEntityDto(
    Integer durationMonths,
    BigDecimal priceStandard,
    BigDecimal priceDiscounted,
    Integer entryLimit,
    Integer freezeDays,
    Boolean isActive,
    java.util.List<az.fitnest.order.model.entity.PlanBenefit> benefits
) {}
