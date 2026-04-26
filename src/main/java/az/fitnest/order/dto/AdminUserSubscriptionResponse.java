package az.fitnest.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
@Schema(description = "İstifadəçinin cari abunəlik detalları (Admin üçün)")
public record AdminUserSubscriptionResponse(
    @Schema(description = "Paket ID-si", example = "1")
    Long packageId,

    @Schema(description = "Paket adı", example = "Premium")
    String packageName,

    @Schema(description = "Seçim (Option) ID-si", example = "10")
    Long optionId,

    @Schema(description = "Seçim müddəti (aylarla)", example = "3")
    Integer optionDuration,

    @Schema(description = "Standart qiymət", example = "150.00")
    BigDecimal price,

    @Schema(description = "Endirimli qiymət", example = "120.00")
    BigDecimal discountedPrice,

    @Schema(description = "Abunəlik başlama tarixi", example = "2023-10-01T10:00:00")
    LocalDateTime startDate,

    @Schema(description = "Abunəlik bitmə tarixi", example = "2024-01-01T10:00:00")
    LocalDateTime endDate,

    @Schema(description = "Paketin ümumi giriş limiti", example = "20")
    Integer totalEntryLimit,

    @Schema(description = "İstifadəçinin qalan giriş limiti", example = "10")
    Integer userRemainingLimit
) {}
