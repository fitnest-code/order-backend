package az.fitnest.order.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record CheckoutRequest(
    @NotNull(message = "Paket ID-si mütləqdir")
    @JsonProperty("package_id")
    @JsonAlias({"packageId"})
    String package_id,

    @NotNull(message = "Option ID-si mütləqdir")
    @JsonProperty("option_id")
    @JsonAlias({"optionId"})
    Long option_id,

    @Deprecated
    @Schema(description = "Köhnə sahə (N2) - qəbul edilir, amma nəzərə alınmır. Ödəniş növü paymentType ilə təyin olunur.", example = "0")
    Integer is_installment,

    @Schema(description = "Promokod (yalnız istifadəçiyə ayrılmış kod)", example = "Bakcell20")
    @JsonProperty("promoCode")
    String promoCode,

    @Schema(description = "FitCoin istifadə edilsin?", example = "false")
    @JsonProperty("useFitCoin")
    Boolean useFitCoin,

    @Schema(description = "Ödəniş növü", example = "CARD")
    @JsonProperty("paymentType")
    String paymentType,

    @Schema(description = "İstifadəçinin gördüyü yekun məbləğ (qiymət dəyişibsə 409)", example = "40.00")
    @JsonProperty("expectedFinalAmount")
    BigDecimal expectedFinalAmount,

    @Schema(description = "Taksit provayderi (TAMKART | BOLKART)")
    @JsonProperty("installmentProvider")
    String installmentProvider,

    @Schema(description = "Taksit ay sayı")
    @JsonProperty("installmentMonths")
    Integer installmentMonths
) {}
