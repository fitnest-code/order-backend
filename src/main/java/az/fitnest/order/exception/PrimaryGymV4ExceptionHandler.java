package az.fitnest.order.exception;

import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {az.fitnest.order.controller.PrimaryGymV4Controller.class})
@Order(-100)
public class PrimaryGymV4ExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(
            Map.of(
                "error",
                Map.of(
                    "code",
                    e.getReason() == null ? "REQUEST_REJECTED" : e.getReason(),
                    "status",
                    e.getStatusCode().value())));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("error", Map.of("code", "INVALID_REQUEST", "status", 400)));
  }

  @ExceptionHandler({
    org.springframework.orm.ObjectOptimisticLockingFailureException.class,
    org.springframework.dao.DataIntegrityViolationException.class
  })
  public ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409)
        .body(Map.of("error", Map.of("code", "CONCURRENT_UPDATE", "status", 409)));
  }
}
