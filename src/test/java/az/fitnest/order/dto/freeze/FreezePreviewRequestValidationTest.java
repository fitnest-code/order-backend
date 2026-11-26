package az.fitnest.order.dto.freeze;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreezePreviewRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void rejectsZeroAndNegativeDays() {
        assertEquals(1, violations(new FreezePreviewRequest(0)).size());
        assertEquals(1, violations(new FreezePreviewRequest(-1)).size());
    }

    @Test
    void rejectsDaysAboveHardCeiling() {
        Set<ConstraintViolation<FreezePreviewRequest>> violations =
                violations(new FreezePreviewRequest(32));

        assertEquals(1, violations.size());
        assertEquals("error.freeze.invalid_days", violations.iterator().next().getMessage());
    }

    @Test
    void acceptsDaysWithinHardCeilingForServiceLevelTierCheck() {
        // The service is authoritative for the per-tier cap; DTO only guards absurd values.
        assertTrue(violations(new FreezePreviewRequest(12)).isEmpty());
    }

    private Set<ConstraintViolation<FreezePreviewRequest>> violations(FreezePreviewRequest request) {
        return validator.validate(request);
    }
}
