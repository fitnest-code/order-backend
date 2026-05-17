package az.fitnest.order.dto;

import java.math.BigDecimal;
import lombok.Builder;

@Builder
public record PackageOptionEntityDto(
    Integer durationMonths,
    BigDecimal priceStandard,
    BigDecimal priceDiscounted,
    Boolean isActive,
    Integer entryLimit
) {}
