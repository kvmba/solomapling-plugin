package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the stat-alignment arithmetic used to keep a bot's worn gear visible.
 *
 * <p>The host's {@code ItemInformationProvider.canWearEquipment(chr, equips)} drops an equip
 * from the look packet when its {@code reqSTR/reqDEX/reqINT/reqLUK} exceeds
 * {@code chr.getStr()/getDex()/getInt()/getLuk()} PLUS the sum of the same stat over the worn
 * equips. {@link BotEquipStats#baseFor} computes the raw stat value that satisfies that rule
 * while never lowering an existing stat.
 */
class BotEquipStatsTest {

    @Test
    void raisesRawStatWhenGearRequirementExceedsIt() {
        // No equip bonus, requirement 90, current 4 -> raw stat must reach 90.
        assertEquals(90, BotEquipStats.baseFor(90, 0, 4));
    }

    @Test
    void equipBonusCountsTowardsTheRequirement() {
        // Requirement 90, but the worn gear itself gives 140 LUK -> raw stat already covers it.
        assertEquals(4, BotEquipStats.baseFor(90, 140, 4));
        // Requirement 90, gear gives 30 -> raw stat needs 60.
        assertEquals(60, BotEquipStats.baseFor(90, 30, 4));
    }

    @Test
    void neverLowersAnExistingStat() {
        assertEquals(200, BotEquipStats.baseFor(90, 0, 200));
        assertEquals(200, BotEquipStats.baseFor(0, 0, 200));
    }

    @Test
    void zeroRequirementLeavesStatUntouched() {
        assertEquals(4, BotEquipStats.baseFor(0, 0, 4));
    }
}
