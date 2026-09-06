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
        // ASCII punctuation and symbols: invisible/control characters are the ones that matter
        // most - a name carrying them looks fine in an editor and breaks in game.
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("has space"));
        // Invisible / control characters: written as Unicode escapes on purpose - a literal one
        // is stripped by editors and tooling, and the assertion would stop testing anything.
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("name\u0001"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("name\u001f"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("name\u007f"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("\u5317\u6597\u4f19\u4f34\u200b"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("\u5317\u6597\u4f19\u4f34\ufeff"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗　伙伴"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗-伙伴"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗_伙伴"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗伙伴♪"));
        // Kana, full-width forms and CJK symbols are not simplified Chinese.
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗の伙伴"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗パートナー"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗伙伴２"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗伙伴丶"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗伙伴〜"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗伙伴°"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("北斗伙伴★"));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName("abcdefghijklmn"));
    }

    @Test
    void rejectsNullNameRatherThanDefaultingIt() {
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProvisioningInput.validateCharacterName(null));
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
