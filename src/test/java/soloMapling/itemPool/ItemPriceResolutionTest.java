package soloMapling.itemPool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pricing regressions behind "rare items are absurdly cheap":
 *
 * <ul>
 *   <li>{@code loadItemDatabase()} omitted the warrior/mage/bowman/chair/mastery
 *       pools, so {@code getItemPrice()} returned null for that gear and every
 *       consumer fell back to a sentinel (100 / 0).</li>
 *   <li>{@code getPriceForVersion()} leaked -1 when every price key was newer
 *       than the running item-pool version.</li>
 *   <li>{@code getPriceForStatBonus()} returned 1L for any stat roll with no
 *       exact band.</li>
 * </ul>
 */
class ItemPriceResolutionTest {

    @Test
    void classPoolsAreLoadedAndPriced() throws Exception {
        ItemDatabase.loadItemDatabase();

        // One weapon id from each class pool that used to be missing entirely.
        Map<Integer, Integer> expected = Map.of(
                1302009, 600_000,   // warrior Lv40 weapon  (warrior.yaml)
                1372012, 600_000,   // mage    Lv40 weapon  (mage.yaml)
                1452007, 600_000,   // bowman  Lv40 weapon  (bowman.yaml)
                1472014, 600_000    // thief   Lv40 claw    (thief.yaml, already loaded)
        );

        for (Map.Entry<Integer, Integer> e : expected.entrySet()) {
            Integer price = ItemDatabase.getInstance().getItemPrice(e.getKey());
            assertNotNull(price, "no price for item " + e.getKey() + " - pool not loaded");
            assertEquals(e.getValue(), price, "wrong price for item " + e.getKey());
        }
    }

    @Test
    void priceForVersionFallsBackWhenAllKeysAreNewerThanThePoolVersion() {
        // Both keys (65, 72) are newer than the default item-pool version (55).
        Map<Integer, Integer> futureOnly = Map.of(65, 2_000_000, 72, 5_000_000);
        int price = ItemSelector.getPriceForVersion(futureOnly);
        assertTrue(price > 0, "future-only price map must not resolve to a negative price");
        assertEquals(2_000_000, price, "should fall back to the earliest known price");
    }

    @Test
    void priceForStatBonusDoesNotCollapseToZero() {
        UniqueStatBonusList list = new UniqueStatBonusList();
        list.addStatBonus(0, 100_000L);
        list.addStatBonus(5, 500_000L);
        list.addStatBonus(10, 2_000_000L);

        // A rare off-band roll above the highest band.
        assertEquals(2_000_000L, UpgradeSimulator.getPriceForStatBonus(list, 15));
        // Between bands -> nearest lower band.
        assertEquals(500_000L, UpgradeSimulator.getPriceForStatBonus(list, 7));
        // Exact hit.
        assertEquals(2_000_000L, UpgradeSimulator.getPriceForStatBonus(list, 10));
    }

    @Test
    void statBonusMapIsSortedSoNearestLowerBandLookupIsWellDefined() {
        UniqueStatBonusList list = new UniqueStatBonusList();
        list.addStatBonus(5, 500_000L);
        list.addStatBonus(0, 100_000L);
        list.addStatBonus(10, 2_000_000L);

        SortedMap<Integer, Long> sorted = list.getStatBonusList();
        assertEquals(List.of(0, 5, 10), List.copyOf(sorted.keySet()));
    }
}
