package soloMapling.companion.survival;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.companion.survival.CompanionSurvivalPolicy.Resource.HP;
import static soloMapling.companion.survival.CompanionSurvivalPolicy.Resource.MP;

class CompanionSurvivalPolicyTest {

    @Test
    void usesHpAndMpOnlyAtTheirIndependentThresholds() {
        assertTrue(CompanionSurvivalPolicy.shouldUse(HP, 600, 1_000));
        assertFalse(CompanionSurvivalPolicy.shouldUse(HP, 601, 1_000));
        assertTrue(CompanionSurvivalPolicy.shouldUse(MP, 350, 1_000));
        assertFalse(CompanionSurvivalPolicy.shouldUse(MP, 351, 1_000));
    }

    @Test
    void choosesSmallestDoseThatCoversTheDeficit() {
        List<CompanionSurvivalPolicy.Potion> potions = List.of(
                potion(2000000, 10, 50, 0, 50),
                potion(2000001, 10, 300, 0, 160),
                potion(2000002, 10, 1_000, 0, 1_500));

        assertEquals(2000001, CompanionSurvivalPolicy
                .chooseForUse(HP, 250, 500, potions).orElseThrow().itemId());
    }

    @Test
    void choosesLargestAvailableDoseWhenNoneCoversTheDeficit() {
        List<CompanionSurvivalPolicy.Potion> potions = List.of(
                potion(2000000, 10, 50, 0, 50),
                potion(2000001, 10, 300, 0, 160));

        assertEquals(2000001, CompanionSurvivalPolicy
                .chooseForUse(HP, 100, 1_000, potions).orElseThrow().itemId());
    }

    @Test
    void purchaseChoiceRequiresAffordabilityAndUsefulRestoration() {
        List<CompanionSurvivalPolicy.Potion> catalog = List.of(
                shopPotion(2000000, 50, 0, 50, (short) 0),
                shopPotion(2000001, 300, 0, 160, (short) 1),
                shopPotion(2000002, 1_000, 0, 1_500, (short) 2));

        assertEquals(2000001, CompanionSurvivalPolicy
                .chooseForPurchase(HP, 1_000, 500, catalog).orElseThrow().itemId());
    }

    @Test
    void stockAndCapacityPoliciesAreBounded() {
        assertTrue(CompanionSurvivalPolicy.needsRestock(11));
        assertFalse(CompanionSurvivalPolicy.needsRestock(12));
        assertEquals(49, CompanionSurvivalPolicy.restockQuantity(11));
        assertEquals(5, CompanionSurvivalPolicy.affordableQuantity(20, 100, 550));
        assertTrue(CompanionSurvivalPolicy.inventoryPressure(2));
        assertFalse(CompanionSurvivalPolicy.inventoryPressure(3));
    }

    @Test
    void routesToKnownRegionalPotionShops() {
        assertEquals(100000102, CompanionSupplyRoute.potionShopFor(100040001));
        assertEquals(101000002, CompanionSupplyRoute.potionShopFor(101030110));
        assertEquals(200000002, CompanionSupplyRoute.potionShopFor(200010100));
        assertEquals(211000102, CompanionSupplyRoute.potionShopFor(211041100));
        assertEquals(220000002, CompanionSupplyRoute.potionShopFor(220030400));
        assertEquals(230000002, CompanionSupplyRoute.potionShopFor(230040200));
        assertEquals(240000002, CompanionSupplyRoute.potionShopFor(240040511));
        assertEquals(261000000, CompanionSupplyRoute.potionShopFor(261020401));
        assertEquals(540000000, CompanionSupplyRoute.potionShopFor(540020500));
        assertEquals(600000000, CompanionSupplyRoute.potionShopFor(600000000));
        assertEquals(800000000, CompanionSupplyRoute.potionShopFor(801040100));
    }

    private static CompanionSurvivalPolicy.Potion potion(
            int itemId, int quantity, int hp, int mp, int price) {
        return new CompanionSurvivalPolicy.Potion(
                itemId, quantity, hp, mp, price, (short) -1);
    }

    private static CompanionSurvivalPolicy.Potion shopPotion(
            int itemId, int hp, int mp, int price, short slot) {
        return new CompanionSurvivalPolicy.Potion(
                itemId, 0, hp, mp, price, slot);
    }
}
