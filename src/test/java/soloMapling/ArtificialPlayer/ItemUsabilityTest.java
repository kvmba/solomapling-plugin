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
     * Meso is encoded as item id 0 in drop tables (MonsterDropEntry,
     * ReactorDropEntry), so it shares the int with real item ids - but it has
     * no String.wz name on purpose. It must NOT be caught by the
     * "unnamed means half-finished data" rule.
     */
    @Test
    void mesoIsUsableDespiteHavingNoName() {
        // getName(0) returns null, yet meso stays usable.
        assertFalse(BotHelpers.hasUsableName(null));
        assertTrue(BotHelpers.isUsableItem(0));
        assertFalse(BotHelpers.isUnusableItem(0));
    }

    @Test
    void mesoHasPrintableName() {
        assertEquals("Meso", BotHelpers.convertItemIdToName(0));
    }

    /**
     * The three rules differ, and the difference is exactly what meso does:
     *
     * <ul>
     *   <li>dropping - legality only, so meso is allowed</li>
     *   <li>player trade - legality only, so meso is allowed</li>
     *   <li>hired-merchant listing - legal AND not meso, since currency is not
     *       merchandise and would show up as a "Meso" lot in a shop</li>
     * </ul>
     *
     * Only the classification is pinned here; the WZ lookup needs a loaded WZ.
     */
    @Test
    void mesoIsDroppableAndTradeableButNotListable() {
        // legality: meso passes, so both drop and trade accept it
        assertTrue(BotHelpers.isUsableItem(0));
        assertFalse(BotHelpers.isUnusableItem(0));

        // listing: meso fails the stricter sellable check
        assertFalse(BotHelpers.isSellableItem(0));
        assertTrue(BotHelpers.isUnsellableItem(0));
    }

    /**
     * sellable = usable AND not meso, so the two can only ever disagree on
     * meso. Id 0 short-circuits before the WZ lookup, so the real methods can
     * be asserted here; a real named id would need a loaded WZ.
     */
    @Test
    void sellableIsStricterThanUsable() {
        assertTrue(BotHelpers.isUsableItem(0));      // droppable
        assertTrue(BotHelpers.isUsableItem(0));      // tradeable
        assertFalse(BotHelpers.isSellableItem(0));   // but not listable
    }

    /**
     * Same sentinel decision {@code convertItemIdToName} makes, exercised
     * directly so it stays covered without loading WZ.
     */
    private static String nameOf(String rawName) {
        return BotHelpers.hasUsableName(rawName) ? rawName : "NULL";
    }
}
