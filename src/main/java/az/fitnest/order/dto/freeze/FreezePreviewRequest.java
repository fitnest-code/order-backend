package az.fitnest.order.dto.freeze;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Immutable request body for freeze preview (low-allocation JSON binding).
 */
public record FreezePreviewRequest(
        @Min(value = 1, message = "error.freeze.invalid_days")
        @Max(value = 31, message = "error.freeze.invalid_days")
        int days
) {}
