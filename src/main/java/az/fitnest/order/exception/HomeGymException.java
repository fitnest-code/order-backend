package az.fitnest.order.exception;

import org.springframework.http.HttpStatus;

/**
 * Home-gym business errors with the stable codes from the spec
 * (HOME_GYM_CHANGE_LOCKED, GYM_NOT_IN_PACKAGE, ...).
 */
public class HomeGymException extends BaseException {

    private static final long serialVersionUID = 1L;

    public HomeGymException(String errorCode, HttpStatus status, String messageKey) {
        super(messageKey, errorCode, status);
    }
}
