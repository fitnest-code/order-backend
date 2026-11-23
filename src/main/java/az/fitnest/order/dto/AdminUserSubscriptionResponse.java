package az.fitnest.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
@Schema(description = "İstifadəçinin cari abunəlik detalları (Admin üçün)")
public record AdminUserSubscriptionResponse(
    @Schema(description = "Abunəlik ID-si", example = "250")
    Long subscriptionId,

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
    Integer userRemainingLimit,

    @Schema(description = "Abunəlik statusu", example = "ACTIVE")
    String status,

    @Schema(description = "Hazırda dondurulubmu")
    Boolean currentlyFrozen,

    @Schema(description = "Aktiv freeze ID (dondurulubsa)")
    Long activeFreezeId,

    @Schema(description = "Dondurulma başlama tarixi")
    LocalDateTime frozenAt,

    @Schema(description = "Planlaşdırılmış açılma tarixi")
    LocalDateTime unfreezesAt,

    @Schema(description = "Ümumi freeze gün hüququ")
    Integer totalFreezeDays,

    @Schema(description = "İstifadə olunmuş freeze günləri")
    Integer consumedFreezeDays,

    @Schema(description = "Rezerv olunmuş freeze günləri")
    Integer reservedFreezeDays,

    @Schema(description = "Qalan (seçilə bilən) freeze günləri")
    Integer availableFreezeDays,

    @Schema(description = "Yeni freeze başlatmaq olar mı")
    Boolean canFreeze
) {}
