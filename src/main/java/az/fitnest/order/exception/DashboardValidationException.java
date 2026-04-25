package az.fitnest.order.exception;

import org.springframework.http.HttpStatus;

import java.util.List;

public class DashboardValidationException extends BaseException {

    private static final long serialVersionUID = 1L;

    private final List<FieldIssue> issues;

    public DashboardValidationException(String field, String issue) {
        this(List.of(new FieldIssue(field, issue)));
    }

    public DashboardValidationException(List<FieldIssue> issues) {
        super("Validation failed for one or more parameters.", "VALIDATION_ERROR", HttpStatus.BAD_REQUEST);
        this.issues = issues;
    }

    public List<FieldIssue> getIssues() {
        return issues;
    }

    public record FieldIssue(String field, String issue) {
    }
}
