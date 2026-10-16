package az.fitnest.order.service.promo;

import lombok.Getter;

/**
 * Thrown when a grant cannot be reserved. Carries the API error code so
 * callers never match on exception message text.
 */
@Getter
public class PromoReservationException extends RuntimeException {

    private final String errorCode;

    public PromoReservationException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }
}
