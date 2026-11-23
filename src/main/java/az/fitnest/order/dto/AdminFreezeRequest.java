package az.fitnest.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "Admin abunəlik dondurma sorğusu")
public record AdminFreezeRequest(
        @Schema(description = "Dondurma müddəti (gün). Abunəliyin qalan freeze günündən çox ola bilməz.", example = "2")
        @Min(value = 1, message = "error.freeze.invalid_days")
        @Max(value = 31, message = "error.freeze.invalid_days")
        int days
) {}
