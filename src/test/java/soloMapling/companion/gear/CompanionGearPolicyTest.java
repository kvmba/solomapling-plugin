package soloMapling.companion.gear;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionGearPolicyTest {

    @Test
    void switchesFromShopAtLevel40ToDropsAfterLevel40() {
        CompanionShopGearPolicy.ShopOffer offer =
                new CompanionShopGearPolicy.ShopOffer(
                        (short) 2, 2_000, armor(1002000, CompanionGearPolicy.Slot.CAP,
                        0, 10, 1, 8, 10));

        assertEquals(CompanionGearPolicy.Mode.SHOP,
                CompanionGearPolicy.modeForLevel(40));
        assertTrue(CompanionShopGearPolicy.chooseUpgrade(
                40, 0, 1, 10_000, 3_000, List.of(offer), List.of()).isPresent());

        assertEquals(CompanionGearPolicy.Mode.DROPS,
                CompanionGearPolicy.modeForLevel(41));
        assertTrue(CompanionShopGearPolicy.chooseUpgrade(
                41, 0, 1, 10_000, 3_000, List.of(offer), List.of()).isEmpty());
    }

    @Test
    void alwaysChoosesWeaponBeforeSecondTierArmor() {
        CompanionGearPolicy.GearItem cap =
                armor(1002001, CompanionGearPolicy.Slot.CAP, 2, 10, 0, 40, 50);
        CompanionGearPolicy.GearItem weapon = weapon(1302001, 2, 10, 1, 12, 0);

        CompanionGearPolicy.GearItem choice = CompanionGearPolicy.bestUpgrade(
                List.of(cap, weapon), List.of(), 30, 0, 1).orElseThrow();

        assertEquals(CompanionGearPolicy.Slot.WEAPON, choice.slot());
    }

    @Test
    void filtersByJobGenderAndRequiredLevel() {
        CompanionGearPolicy.GearItem wrongJob = weapon(1302002, 2, 10, 2, 30, 0);
        CompanionGearPolicy.GearItem wrongGender = weapon(1302003, 1, 10, 1, 29, 0);
        CompanionGearPolicy.GearItem tooHigh = weapon(1302004, 2, 31, 1, 28, 0);
        CompanionGearPolicy.GearItem wearable = weapon(1302005, 2, 30, 1, 27, 0);

        assertFalse(CompanionGearPolicy.canWear(wrongJob, 30, 0, 1));
        assertFalse(CompanionGearPolicy.canWear(wrongGender, 30, 0, 1));
        assertFalse(CompanionGearPolicy.canWear(tooHigh, 30, 0, 1));
        assertTrue(CompanionGearPolicy.canWear(wearable, 30, 0, 1));
        assertEquals(wearable.itemId(), CompanionGearPolicy.bestUpgrade(
                List.of(wrongJob, wrongGender, tooHigh, wearable),
                List.of(), 30, 0, 1).orElseThrow().itemId());
    }

    @Test
    void replacesByActualStatsRatherThanItemId() {
        CompanionGearPolicy.GearItem equipped =
                weapon(1492999, 2, 1, 16, 25, 2);
        CompanionGearPolicy.GearItem lowerIdUpgrade =
                weapon(1492001, 2, 1, 16, 27, 1);
        CompanionGearPolicy.GearItem higherIdDowngrade =
                weapon(1499999, 2, 1, 16, 20, 0);

        assertTrue(CompanionGearPolicy.isUpgrade(lowerIdUpgrade, equipped));
        assertFalse(CompanionGearPolicy.isUpgrade(higherIdDowngrade, equipped));
        assertEquals(lowerIdUpgrade.itemId(), CompanionGearPolicy.bestUpgrade(
                List.of(lowerIdUpgrade, higherIdDowngrade), List.of(equipped),
                30, 0, 16).orElseThrow().itemId());
    }

    @Test
    void postLevel40GoalContainsBestDropSource() {
        CompanionGearPolicy.GearItem item = weapon(1452005, 2, 35, 4, 40, 0);
        GearDropSourceProvider provider = ignored -> List.of(
                new GearDropSourceProvider.DropSource(
                        100100, "Snail", 100000000, "Southperry", 0.001, false),
                new GearDropSourceProvider.DropSource(
                        2220000, "Red Snail", 100020000, "Henesys Hunting Ground", 0.003, true));

        CompanionGearGoal goal = CompanionDropGearPolicy.chooseGoal(
                41, 0, 4, List.of(item), List.of(), provider).orElseThrow();

        assertEquals(item.itemId(), goal.itemId());
        assertEquals(2220000, goal.mobId());
        assertEquals(0.003, goal.chance());
    }

    @Test
    void keepsPotionReserveOutOfShopBudget() {
        CompanionShopGearPolicy.ShopOffer expensive =
                new CompanionShopGearPolicy.ShopOffer(
                        (short) 0, 9_000,
                        armor(1002000, CompanionGearPolicy.Slot.CAP,
                                2, 1, 0, 1, 1));

        assertTrue(CompanionShopGearPolicy.chooseUpgrade(
                20, 0, 1, 10_000, 2_000,
                List.of(expensive), List.of()).isEmpty());
    }

    private static CompanionGearPolicy.GearItem weapon(
            int id, int gender, int level, int job, int watk, int matk) {
        return new CompanionGearPolicy.GearItem(
                id, "weapon-" + id, CompanionGearPolicy.Slot.WEAPON,
                gender, level, job,
                new CompanionGearPolicy.Stats(watk, matk, 0, 0, 0, 0, 0, 0));
    }

    private static CompanionGearPolicy.GearItem armor(
            int id,
            CompanionGearPolicy.Slot slot,
            int gender,
            int level,
            int job,
            int attributes,
            int defense) {
        return new CompanionGearPolicy.GearItem(
                id, "armor-" + id, slot, gender, level, job,
                new CompanionGearPolicy.Stats(
                        0, 0, attributes, 0, 0, 0, defense, 0));
    }
}
