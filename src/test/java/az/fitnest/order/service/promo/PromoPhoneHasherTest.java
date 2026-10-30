package az.fitnest.order.service.promo;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for phone normalisation / validation (FR-AUD-2 to FR-AUD-4, AC-1).
 */
class PromoPhoneHasherTest {

    private final PromoPhoneHasher hasher = new PromoPhoneHasher();

    PromoPhoneHasherTest() {
        ReflectionTestUtils.setField(hasher, "hmacKey", "test-key");
    }

    @Test
    void normalizePhone_variousFormats() {
        assertEquals("+994554123318", hasher.normalizePhone("055 412 33 18"));
        assertEquals("+994554123318", hasher.normalizePhone("994554123318"));
        assertEquals("+994551234567", hasher.normalizePhone("55-123-45-67"));
        assertEquals("+994551234567", hasher.normalizePhone("551234567"));
        assertEquals("+994554123318", hasher.normalizePhone("(055) 412 33 18"));
        assertEquals("+994994123456", hasher.normalizePhone("099 412 34 56"));
    }

    @Test
    void hashPhone_isDeterministicAndHex64() {
        String h1 = hasher.hashPhone("+994554123318");
        String h2 = hasher.hashPhone("+994554123318");
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
        assertTrue(h1.matches("^[0-9a-f]{64}$"));
    }

    @Test
    void validPrefixes() {
        String[] bakcell = {"55", "99"};
        assertTrue(hasher.isValidAzerbaijanMobile("+994554123318", bakcell));
        assertTrue(hasher.isValidAzerbaijanMobile("+994994123456", bakcell));
        assertFalse(hasher.isValidAzerbaijanMobile("+994504123318", bakcell));
    }

    @Test
    void defaultPrefixes_whenNoneProvided() {
        assertTrue(hasher.isValidAzerbaijanMobile("+994554123318", new String[0]));
        assertTrue(hasher.isValidAzerbaijanMobile("+994504123318", new String[0]));
        assertFalse(hasher.isValidAzerbaijanMobile("+994944123318", new String[0]));
    }

    @Test
    void invalidNumbersReturnNull() {
        assertNull(hasher.normalizePhone("12345"));
        assertNull(hasher.normalizePhone("abc"));
        assertNull(hasher.normalizePhone(null));
    }
}
