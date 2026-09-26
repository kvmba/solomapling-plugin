package soloMapling.itemPool;

import org.gms.constants.inventory.EquipType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShopEquipPool turns the host's shopitems rows into a per-EquipType pool of
 * wearable gear a bot can be dressed in. The DB read and the metadata join are
 * separate seams ({@code readShopEquipIds} / {@link #buildPool}), so these
 * tests run fully in memory against a fake EquipEntry list:
 *
 * <ul>
 *   <li>the join decides what enters the pool: duplicates are irrelevant
 *       (already deduped by the SQL read), cash items never enter, and without
 *       an initialized metadata cache the pool stays empty (fail-closed)</li>
 *   <li>draws honour reqLevel (never over-level), gender, reqJob bitmask and
 *       the central {@link EquipOmitList}</li>
 * </ul>
 */
public class ShopEquipPoolTest {

    private static final int CAP_ID = 1002001;    // reqLevel 0, reqJob 0, unisex
    private static final int TOP_ID = 1040002;    // reqLevel 0, reqJob 0, male (4th digit 0)
    private static final int FTOP_ID = 1041002;   // reqLevel 0, reqJob 0, female (4th digit 1)
    private static final int LV35_TOP_ID = 1040099;         // reqLevel 35, reqJob 0, male
    private static final int LV50_WARRIOR_SWORD = 1302050;  // reqLevel 50, reqJob 1 (warrior)
    private static final int CASH_CAP_ID = 1002999;         // stocked by a shop, but cash in WZ

    private static EquipMetadataCache.EquipEntry entry(int id, EquipType type, int gender,
                                                       int reqLevel, int reqJob, boolean cash) {
        return new EquipMetadataCache.EquipEntry(id, type, gender, reqLevel, reqJob, cash, false, false, 1, "?");
    }

    @Test
    public void joinKeepsEquipsAndDropsCash() {
        Map<EquipType, List<EquipMetadataCache.EquipEntry>> pool = ShopEquipPool.buildPool(
                List.of(CAP_ID, TOP_ID, CASH_CAP_ID),
                List.of(entry(CAP_ID, EquipType.CAP, 2, 0, 0, false),
                        entry(TOP_ID, EquipType.COAT, 0, 0, 0, false),
                        entry(CASH_CAP_ID, EquipType.CAP, 2, 0, 0, true)));

        assertEquals(1, pool.get(EquipType.CAP).size(), "cash items must not enter the pool");
        assertEquals(CAP_ID, pool.get(EquipType.CAP).get(0).id);
        assertEquals(1, pool.get(EquipType.COAT).size());
        assertTrue(ShopEquipPool.size() == 0, "buildPool is pure; the published pool is untouched");
    }

    @Test
    public void joinWithoutMetadataFailsClosed() {
        Map<EquipType, List<EquipMetadataCache.EquipEntry>> pool = ShopEquipPool.buildPool(
                List.of(CAP_ID), List.of());

        assertTrue(pool.values().stream().allMatch(List::isEmpty),
                "without WZ metadata nothing can be validated, so the pool stays empty");
    }

    @Test
    public void joinIgnoresIdsOutsideTrackedEquipTypes() {
        Map<EquipType, List<EquipMetadataCache.EquipEntry>> pool = ShopEquipPool.buildPool(
                List.of(2000000),
                List.of(entry(CAP_ID, EquipType.CAP, 2, 0, 0, false)));

        assertTrue(pool.values().stream().allMatch(List::isEmpty),
                "non-equip shop rows must not reach the pool");
        assertTrue(ShopEquipPool.isEquipId(1002001));
        assertFalse(ShopEquipPool.isEquipId(2000000), "use items are not equips");
        assertFalse(ShopEquipPool.isEquipId(5000000), "pets are not equips");
        assertFalse(ShopEquipPool.isEquipId(2040000), "scrolls are not equips");
    }

    @Test
    public void drawStaysInsideTheLevelWindow() {
        List<EquipMetadataCache.EquipEntry> coats = List.of(
                entry(TOP_ID, EquipType.COAT, 0, 0, 0, false),
                entry(LV35_TOP_ID, EquipType.COAT, 0, 35, 0, false));

        // lv20 window: max(25%, 10) -> reqLevel 15..20; both coats fall outside.
        assertNull(ShopEquipPool.draw(coats, 20, 0, 0),
                "gear far below the level window must not be drawn — caller falls back to the WZ pool");
        // lv35 window: 26..35 -> only the lv35 coat qualifies; the lv0 one never shows up.
        for (int i = 0; i < 50; i++) {
            assertEquals(LV35_TOP_ID, ShopEquipPool.draw(coats, 35, 0, 0),
                    "the lv0 coat must never be drawn at lv35 (window 26..35)");
        }
        // lv40 window: 30..40 -> still only the lv35 coat.
        assertEquals(LV35_TOP_ID, ShopEquipPool.draw(coats, 40, 0, 0));
    }

    @Test
    public void windowFloorIsAtLeastTenLevels() {
        // A lv5 bot has a 10-level floor: window -5..5, so the lv0 top fits.
        List<EquipMetadataCache.EquipEntry> coats = List.of(
                entry(TOP_ID, EquipType.COAT, 0, 0, 0, false));
        assertEquals(TOP_ID, ShopEquipPool.draw(coats, 5, 0, 0),
                "max(25%, 10) floor keeps low-level bots in shop starter gear");
    }

    @Test
    public void genderGateHolds() {
        List<EquipMetadataCache.EquipEntry> coats = List.of(
                entry(TOP_ID, EquipType.COAT, 0, 0, 0, false),
                entry(FTOP_ID, EquipType.COAT, 1, 0, 0, false));

        for (int i = 0; i < 20; i++) {
            assertEquals(TOP_ID, ShopEquipPool.draw(coats, 10, 0, 0),
                    "male bots draw male/unisex gear only");
            assertEquals(FTOP_ID, ShopEquipPool.draw(coats, 10, 0, 1),
                    "female bots draw female/unisex gear only");
        }
    }

    @Test
    public void jobGateHolds() {
        List<EquipMetadataCache.EquipEntry> swords = List.of(
                entry(LV50_WARRIOR_SWORD, EquipType.SWORD, 2, 50, 1, false));

        assertEquals(LV50_WARRIOR_SWORD, ShopEquipPool.draw(swords, 50, 1, 0),
                "a warrior (bit 1) may draw the reqJob-1 sword");
        assertNull(ShopEquipPool.draw(swords, 50, 4, 0),
                "a bowman (bit 4) must not draw the reqJob-1 sword");
        assertNull(ShopEquipPool.draw(swords, 49, 1, 0),
                "reqLevel window still gates even when the job matches");
    }

    @Test
    public void omittedIdsAreNeverDrawn() {
        // 1302073 (Singapore Flag) is on EquipOmitList.yaml; any id the central
        // list blocks must be skipped here regardless of shop stock.
        int omitted = 1302073;
        List<EquipMetadataCache.EquipEntry> swords = List.of(
                entry(omitted, EquipType.SWORD, 2, 0, 0, false));

        assertNull(ShopEquipPool.draw(swords, 10, 0, 2),
                "omitted ids (flag poles etc.) must be skipped even when shop-sold");
    }

    @Test
    public void emptyOrNullListFailsClosed() {
        assertNull(ShopEquipPool.draw(null, 10, 0, 2));
        assertNull(ShopEquipPool.draw(List.of(), 10, 0, 2));
    }
}
