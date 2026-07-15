package az.fitnest.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "İstifadəçinin cari abunəliyinin giriş limitini yeniləmək üçün sorğu (Admin üçün)")
public record UpdateEntryLimitRequest(

        @Schema(description = "İstifadəçinin yeni qalan giriş limiti", example = "10")
        @NotNull(message = "error.remaining_limit_required")
        @Min(value = 0, message = "error.remaining_limit_negative")
        Integer remainingLimit,

        @Schema(description = "Ümumi giriş limiti (opsional). Verilmədikdə qalan limitə uyğunlaşdırılır.", example = "12")
        @Min(value = 0, message = "error.total_limit_negative")
        Integer totalLimit
) {}
