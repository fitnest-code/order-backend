package az.fitnest.order.exception;

import az.fitnest.order.controller.AdminDashboardController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.WebRequest;

import java.util.List;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AdminDashboardController.class)
public class DashboardExceptionHandler {

    @ExceptionHandler(DashboardValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(DashboardValidationException ex) {
        List<Map<String, String>> details = ex.getIssues().stream()
                .map(issue -> Map.of("field", issue.field(), "issue", issue.issue()))
                .toList();
        return ResponseEntity.badRequest().body(Map.of(
                "error", Map.of(
                        "code", "VALIDATION_ERROR",
                        "message", ex.getMessage(),
                        "details", details
                )
        ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String field = ex.getName() == null ? "request" : ex.getName();
        String issue = "invalid value";
        if (ex.getRequiredType() != null && ex.getRequiredType().getSimpleName().equalsIgnoreCase("LocalDate")) {
            issue = "must be ISO date (YYYY-MM-DD)";
        }
        return ResponseEntity.badRequest().body(Map.of(
                "error", Map.of(
                        "code", "VALIDATION_ERROR",
                        "message", "Validation failed for one or more parameters.",
                        "details", List.of(Map.of("field", field, "issue", issue))
                )
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", Map.of(
                        "code", "VALIDATION_ERROR",
                        "message", "Validation failed for one or more parameters.",
                        "details", List.of(Map.of(
                                "field", ex.getParameterName(),
                                "issue", "is required"
                        ))
                )
        ));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", Map.of(
                        "code", "FORBIDDEN",
                        "message", ex.getMessage()
                )
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", Map.of(
                        "code", "INTERNAL_ERROR",
                        "message", "An unexpected error occurred. Please try again later."
                )
        ));
    }
}
