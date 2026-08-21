package soloMapling.companion.provisioning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionProvisioningInputTest {

    @Test
    void acceptsHostCompatibleCharacterNames() {
        assertEquals("Mira42", CompanionProvisioningInput.validateCharacterName("Mira42"));
        assertEquals("北斗伙伴", CompanionProvisioningInput.validateCharacterName("北斗伙伴"));
    }

    @Test
    void rejectsUnsafeOrOutOfRangeNames() {
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("a"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("has space"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("abcdefghijklmn"));
    }

    @Test
    void validatesNumericArgumentsWithoutFallbacks() {
        assertEquals(42, CompanionProvisioningInput.parseCharacterId("42"));
        assertEquals(Long.MIN_VALUE,
                CompanionProvisioningInput.parsePersonaSeed(Long.toString(Long.MIN_VALUE)));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.parseCharacterId("0"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.parsePersonaSeed("random"));
    }
}
