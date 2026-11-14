package az.fitnest.order.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void acceptsCamelCaseAliases() throws Exception {
        CheckoutRequest request = MAPPER.readValue(
                "{\"packageId\":\"12\",\"optionId\":34}", CheckoutRequest.class);

        assertEquals("12", request.package_id());
        assertEquals(34L, request.option_id());
        assertTrue(validator.validate(request).isEmpty(), "camelCase must satisfy @NotNull");
    }

    @Test
    void acceptsSnakeCase() throws Exception {
        CheckoutRequest request = MAPPER.readValue(
                "{\"package_id\":\"12\",\"option_id\":34}", CheckoutRequest.class);

        assertEquals("12", request.package_id());
        assertEquals(34L, request.option_id());
        assertTrue(validator.validate(request).isEmpty(), "snake_case must satisfy @NotNull");
    }

    @Test
    void rejectsMissingPackageAndOption() throws Exception {
        CheckoutRequest request = MAPPER.readValue("{}", CheckoutRequest.class);

        assertEquals(2, validator.validate(request).size());
    }
}
