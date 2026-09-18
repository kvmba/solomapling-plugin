package soloMapling.itemPool;

import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.EquipListGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pricing regressions behind "rare gear is absurdly cheap".
 *
 * <p>WZ price is a shop-resale figure, not a market value, so BIS / boss-unique
 * gear (Zakum Helmet, Brown Work Gloves, …) carried a tiny WZ price and listed
 * for less than ordinary gear. {@code rareItemPrices.yaml} overrides that with a
 * 国服-style market price, applied as a floor by
 * {@link EquipListGenerator#applyRarePriceOverride}.
 */
class RareEquipPricingTest {

    @Test
    void rareOverridesAreLoaded() {
        DesirableEquipList.load();
        assertNotNull(DesirableEquipList.getRarePrice(1002357), "Zakum Helmet must have an override");
        assertNotNull(DesirableEquipList.getRarePrice(1082149), "Brown Work Gloves must have an override");
        assertNotNull(DesirableEquipList.getRarePrice(1122000), "Horntail Necklace must have an override");
    }

    @Test
    void rareOverrideLiftsACheapPriceToItsMarketValue() {
        DesirableEquipList.load();
        int rare = DesirableEquipList.getRarePrice(1002357);

        // The WZ-derived price of a Zakum Helmet is a couple hundred k at most.
        assertEquals(rare, EquipListGenerator.applyRarePriceOverride(1002357, 97_500));
    }

    @Test
    void rareOverrideIsAFloorNotACeiling() {
        DesirableEquipList.load();
        int rare = DesirableEquipList.getRarePrice(1002357);

        // A genuinely higher scrolled valuation must be preserved.
        int scrolled = rare + 50_000_000;
        assertEquals(scrolled, EquipListGenerator.applyRarePriceOverride(1002357, scrolled));
    }

    @Test
    void unlistedItemsAreUnchanged() {
        DesirableEquipList.load();
        // An ordinary Lv70 weapon id that is not in the rare table.
        assertEquals(675_000, EquipListGenerator.applyRarePriceOverride(1472026, 675_000));
    }

    @Test
    void rareGearOutvaluesOrdinaryGear() {
        DesirableEquipList.load();
        // The whole point: an iconic BIS item must be worth well over common gear.
        int bwg = DesirableEquipList.getRarePrice(1082149);
        assertTrue(bwg > 10_000_000, "Brown Work Gloves must clearly outvalue ordinary gear, was " + bwg);
    }
}
