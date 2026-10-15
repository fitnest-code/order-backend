package az.fitnest.order.dto.promo;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromoPartnerRequest {

    @NotBlank(message = "Partner name is required")
    @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
    private String name;

    private String logoUrl;

    private String logoOnDarkUrl;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Brand color must be a valid hex color")
    private String brandColor;

    private String contactName;

    @Email(message = "Contact email must be valid")
    private String contactEmail;

    private String agreementRef;

    @Builder.Default
    @Min(value = 1, message = "Retention days must be at least 1")
    @Max(value = 730, message = "Retention days must be at most 730")
    private Integer retentionDays = 90;

    private List<String> defaultPrefixes;

    @Builder.Default
    private Boolean status = true;
}