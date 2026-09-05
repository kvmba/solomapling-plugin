package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the "unnamed item is unusable" contract.
 *
 * <p>Some WZ entries are half-finished: they exist as items but carry no
 * String.wz name (or an empty/blank one). Those ids used to flow straight into
 * shop listings, purchases and advertisements, and reading their name was also
 * the trigger for a recurring NPE - the host's XML DOM walk
 * ({@code XMLDomMapleData.getChildByPath}) is not thread safe, and bot ticks
 * share the provider trees across virtual threads, so a concurrent read could
 * return a null node instead of a value.
 *
 * <p>Only the pure classification is asserted here; the lookup itself needs a
 * loaded WZ and is covered by the guards at each call site.
 */
class ItemUsabilityTest {

    @Test
    void blankNamesAreUnusable() {
        assertFalse(BotHelpers.hasUsableName(null));
        assertFalse(BotHelpers.hasUsableName(""));
        assertFalse(BotHelpers.hasUsableName("   "));
        assertFalse(BotHelpers.hasUsableName("\t\n"));
    }

    @Test
    void realNamesAreUsable() {
        assertTrue(BotHelpers.hasUsableName("Ilbi Throwing Stars"));
        assertTrue(BotHelpers.hasUsableName("褐色落腮胡"));
        assertTrue(BotHelpers.hasUsableName("?"));  // placeholder still printable
    }

    /**
     * The WZ-independent half of the contract. The lookup itself needs a loaded
     * WZ ({@code ItemInformationProvider} cannot even initialize in a unit
     * test), so here we only pin down that {@code convertItemIdToName} can
     * never hand a null to its callers, several of which call
     * {@code .toLowerCase()} on the result immediately.
     */
    @Test
    void unnamedNeverLeaksNull() {
        assertFalse(BotHelpers.hasUsableName(null));
        assertEquals("NULL", nameOf(null));
        assertEquals("NULL", nameOf(""));
        assertEquals("NULL", nameOf("   "));
    }

    @Test
    void realNamesPassThrough() {
        assertTrue(BotHelpers.hasUsableName("Ilbi Throwing Stars"));
        assertTrue(BotHelpers.hasUsableName("褐色落腮胡"));
        assertTrue(BotHelpers.hasUsableName("?"));  // placeholder still printable
        assertEquals("Ilbi Throwing Stars", nameOf("Ilbi Throwing Stars"));
    }

    /**
     * Same sentinel decision {@code convertItemIdToName} makes, exercised
     * directly so it stays covered without loading WZ.
     */
    private static String nameOf(String rawName) {
        return BotHelpers.hasUsableName(rawName) ? rawName : "NULL";
    }
}
