package az.fitnest.order.service.freeze;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import java.util.Locale;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreezeTierPolicyProviderTest {

    private final FreezeTierPolicyProvider provider = new FreezeTierPolicyProvider();

    @ParameterizedTest
    @CsvSource({
            "Bronze, 5",
            "Silver, 7",
            "Gold, 10",
            "Platinum, 12"
    })
    void returnsV12AllowanceForEachTier(String packageName, int expectedDays) {
        assertEquals(expectedDays, provider.getAllowedDays(packageName));
    }

    @Test
    void tierDetectionIsCaseAndLocaleIndependent() {
        assertEquals(12, provider.getAllowedDays("PLATINUM 12 AY"));
        assertEquals(10, provider.getAllowedDays("gold"));
    }

    @Test
    void tierDetectionWorksUnderTurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(12, provider.getAllowedDays("PLATINUM"));
            assertEquals(5, provider.getAllowedDays("BRONZE"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void unknownPackageHasNoAllowance() {
        assertEquals(0, provider.getAllowedDays(null));
        assertEquals(0, provider.getAllowedDays("Trial"));
        assertFalse(provider.qualifiesForEntitlement("Trial"));
        assertTrue(provider.qualifiesForEntitlement("Bronze"));
    }

    @Test
    void exposesV12PolicyVersion() {
        assertEquals("v1.2", provider.getPolicyVersion());
    }
}
