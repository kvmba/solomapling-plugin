package soloMapling.companion.provisioning;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureCompanionIdentityGeneratorTest {

    @Test
    void generatesSchemaCompatibleDistinctAccountNames() {
        SecureCompanionIdentityGenerator generator = new SecureCompanionIdentityGenerator();
        Set<String> names = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            String name = generator.nextAccountName();
            assertEquals(13, name.length());
            assertTrue(name.matches("cmp_[a-z2-9]{9}"));
            assertTrue(names.add(name));
        }
    }

    @Test
    void generatesRandomNonPrintableSafeCredentials() {
        SecureCompanionIdentityGenerator generator = new SecureCompanionIdentityGenerator();
        char[] first = generator.nextCredential();
        char[] second = generator.nextCredential();

        assertEquals(48, first.length);
        assertTrue(new String(first).matches("[A-Za-z0-9_-]{48}"));
        assertFalse(Arrays.equals(first, second));
        Arrays.fill(first, '\0');
        Arrays.fill(second, '\0');
    }

    @Test
    void personaSeedsAreGeneratedRatherThanFixed() {
        SecureCompanionIdentityGenerator generator = new SecureCompanionIdentityGenerator();
        assertNotEquals(generator.nextPersonaSeed(), generator.nextPersonaSeed());
    }
}
