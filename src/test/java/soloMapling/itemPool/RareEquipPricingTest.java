package soloMapling.itemPool;

import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.EquipListGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pricing regressions behind "rare gear is absurdly cheap".
 *
 * <p>WZ price is a shop-resale figure, not a market value, so BIS / boss-unique
 * gear carried a tiny WZ price and listed for less than ordinary gear.
 * {@code rareItemPrices.yaml} overrides that with a 国服-style market price,
 * applied as a floor by {@link EquipListGenerator#applyRarePriceOverride}.
 */
class RareEquipPricingTest {

    @Test
    void rareOverridesAreLoaded() {
        DesirableEquipList.load();
        assertNotNull(DesirableEquipList.getRarePrice(1002390), "Zakum Helmet (2) must have an override");
        assertNotNull(DesirableEquipList.getRarePrice(1082149), "Brown Work Gloves must have an override");
        assertNotNull(DesirableEquipList.getRarePrice(1082223), "Stormcaster Gloves must have an override");
    }

    @Test
    void untradeableGearIsNotInTheTable() {
        DesirableEquipList.load();
        // tradeBlock=1 items can never reach a shop, so an override would be dead
        // weight (and the old table wrongly listed the untradeable Zakum Helmet).
        assertNull(DesirableEquipList.getRarePrice(1002357), "untradeable Zakum Helmet must not be listed");
        assertNull(DesirableEquipList.getRarePrice(1122000), "untradeable Horntail Necklace must not be listed");
    }

    @Test
    void iconicGearStaysOnTheWhitelistEvenWhenUntradeable() {
        DesirableEquipList.load();
        // The whitelist is dual-purpose: shops use it to let low-level/low-WZ gear
        // reach a shelf, and bot dialogue (DialogueContextResolver) uses it to
        // rank a player's worn gear as "genuinely good". Iconic-but-untradeable
        // items (Zakum Helmet) must therefore stay listed even though no override
        // applies to them.
        assertTrue(DesirableEquipList.isDesirable(1002357), "Zakum Helmet must stay whitelisted for dialogue");
        assertTrue(DesirableEquipList.isDesirable(1082149), "Brown Work Gloves must stay whitelisted");
        assertTrue(DesirableEquipList.isDesirable(1472030), "Maple Claw must stay whitelisted");
    }

    @Test
    void rareOverrideLiftsACheapPriceToItsMarketValue() {
        DesirableEquipList.load();
        int rare = DesirableEquipList.getRarePrice(1082149);
        // Brown Work Gloves price from WZ is only a few thousand mesos.
        assertEquals(rare, EquipListGenerator.applyRarePriceOverride(1082149, 5_850));
    }

    @Test
    void rareOverrideIsAFloorNotACeiling() {
        DesirableEquipList.load();
        int rare = DesirableEquipList.getRarePrice(1082149);
        int scrolled = rare + 50_000_000; // a genuinely higher scrolled valuation
        assertEquals(scrolled, EquipListGenerator.applyRarePriceOverride(1082149, scrolled));
    }

    @Test
    void unlistedItemsAreUnchanged() {
        DesirableEquipList.load();
        assertEquals(675_000, EquipListGenerator.applyRarePriceOverride(1472026, 675_000));
    }

    @Test
    void rareGearOutvaluesOrdinaryGear() {
        DesirableEquipList.load();
        int bwg = DesirableEquipList.getRarePrice(1082149);
        assertTrue(bwg > 10_000_000, "Brown Work Gloves must clearly outvalue ordinary gear, was " + bwg);
    }
}
