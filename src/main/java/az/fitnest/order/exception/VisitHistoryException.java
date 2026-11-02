package az.fitnest.order.exception;

import org.springframework.http.HttpStatus;

/** Visit-history validation failures with the stable codes from the spec. */
public class VisitHistoryException extends BaseException {

    private static final long serialVersionUID = 1L;

    public VisitHistoryException(String errorCode, String messageKey) {
        super(messageKey, errorCode, HttpStatus.BAD_REQUEST);
    }
}
