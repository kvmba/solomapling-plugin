package soloMapling.companion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionRosterTest {

    @AfterEach
    void clearRoster() {
        CompanionRoster.clear();
    }

    @Test
    void tracksNativeCharacterIdsWithoutLegacyRangeAssumptions() {
        CompanionRoster.register(42);

        assertTrue(CompanionRoster.isCompanion(42));
        assertTrue(CompanionRoster.characterIds().contains(42));

        CompanionRoster.unregister(42);
        assertFalse(CompanionRoster.isCompanion(42));
    }

    @Test
    void snapshotCannotMutateRoster() {
        CompanionRoster.register(73);

        assertThrows(UnsupportedOperationException.class,
                () -> CompanionRoster.characterIds().add(74));
        assertTrue(CompanionRoster.isCompanion(73));
        assertFalse(CompanionRoster.isCompanion(74));
    }

    @Test
    void rejectsNonPositiveCharacterIds() {
        assertThrows(IllegalArgumentException.class, () -> CompanionRoster.register(0));
        assertThrows(IllegalArgumentException.class, () -> CompanionRoster.register(-1));
    }
}
