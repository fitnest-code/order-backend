package az.fitnest.order.dto.promo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PromoQuoteRequest {

    /**
     * Catalog package id (string form, e.g. "12"). Preferred identifier (B13).
     * Legacy alias {@code planId} still accepted and means the same thing.
     */
    private String packageId;

    /** @deprecated use packageId */
    @Deprecated
    private String planId;

    /** Catalog option id within the package (duration/price variant). */
    private Long optionId;

    private String promoCode;

    private Boolean useFitCoin = false;

    /** CARD, APPLE_PAY, GOOGLE_PAY, INSTALLMENT */
    private String paymentType;

    /** TAMKART | BOLKART - required when paymentType=INSTALLMENT and bank matters */
    private String installmentProvider;

    private Integer installmentMonths;

    private BigDecimal basePrice;
}
