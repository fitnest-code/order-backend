package az.fitnest.order.dto.promo;

import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromoCampaignRequest {

    private UUID partnerId;

    @NotBlank(message = "Campaign name is required")
    @Size(min = 3, max = 120, message = "Name must be 3-120 characters")
    private String name;

    @NotBlank(message = "Promo code is required")
    @Size(min = 4, max = 16, message = "Code must be 4-16 characters")
    @Pattern(regexp = "^[A-Za-z0-9]+$", message = "Code must contain only letters and digits")
    private String code;

    @NotNull(message = "Discount type is required")
    private String discountType; // PERCENT, FIXED

    @NotNull(message = "Discount value is required")
    @DecimalMin(value = "1", message = "Percentage must be at least 1")
    @DecimalMax(value = "100", message = "Percentage must be at most 100")
    private BigDecimal discountValue;

    @DecimalMin(value = "0.01", message = "Max discount must be positive")
    private BigDecimal maxDiscountAmount;

    @NotNull(message = "Audience type is required")
    private String audienceType; // UPLOADED_LIST, NEW_USERS, ALL_USERS

    private List<String> allowedPrefixes;

    @NotNull(message = "At least one plan is required")
    @Size(min = 1, message = "At least one plan is required")
    private List<UUID> planIds;

    private List<String> tierCodes;

    @NotNull(message = "Start date is required")
    private OffsetDateTime startsAt;

    @NotNull(message = "End date is required")
    private OffsetDateTime endsAt;

    @Builder.Default
    @Min(value = 1, message = "Uses per user must be at least 1")
    private Integer usesPerUser = 1;

    private Integer totalLimit;

    @Builder.Default
    private Integer priority = 0;

    @Builder.Default
    private Boolean firstPaymentOnly = true;

    @Builder.Default
    private Boolean allowFitcoin = true;

    @Builder.Default
    private Boolean allowInstallments = true;

    @Builder.Default
    private Boolean includeExistingUsers = true;

    private Integer version;

    // Translations
    private List<AdminPromoCampaignTranslationRequest> translations;

    // Popup
    private AdminPromoCampaignPopupRequest popup;
}