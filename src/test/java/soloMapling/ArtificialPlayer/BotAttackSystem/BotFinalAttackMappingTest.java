package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.inventory.WeaponType;
import org.gms.constants.skills.Crossbowman;
import org.gms.constants.skills.Fighter;
import org.gms.constants.skills.Hunter;
import org.gms.constants.skills.Page;
import org.gms.constants.skills.Spearman;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The job/weapon -> final-attack table behind 终极攻击, as a pure mapping.
 *
 * <p>This pins a real defect: the branch was written {@code warrior && a || b}, so a NON-warrior
 * holding a 2H sword fell into the sword branch and fired 终极剑 (a bowman/thief is refused by a
 * job gate placed after the mapping, and the container-side job check is disabled in this host's
 * {@code canWearEquipment}, so "impossible" was not actually enforced). The warrior gate must span
 * the whole warrior condition.
 *
 * <p>Job ids used here are the real v83 explorer ids: Fighter 110-112, Page 120-122,
 * Spearman/DK 130-132, Hunter 310-312, Crossbowman 320-322, plus a thief (412) and a beginner (0)
 * for the negative cases.
 */
class BotFinalAttackMappingTest {

    private static final int[] WARRIOR_JOBS = {100, 110, 111, 112, 120, 121, 122, 130, 131, 132};
    private static final int THIEF = 412;
    private static final int BEGINNER = 0;
    private static final int BOWMAN_HUNTER = 312;
    private static final int BOWMAN_CROSSBOW = 322;

    @Test
    void aTwoHandSwordIsTheFighterSwordPassiveForWarriorsOnly() {
        for (int job : WARRIOR_JOBS) {
            assertEquals(Fighter.FINAL_ATTACK_SWORD, BotFinalAttack.skillIdFor(job, WeaponType.SWORD2H),
                    "warrior " + job + " + 2H sword -> 终极剑");
        }
        // The regression: a non-warrior holding a 2H sword must map to NOTHING.
        assertEquals(0, BotFinalAttack.skillIdFor(THIEF, WeaponType.SWORD2H),
                "a thief must never fire the warrior's 终极剑");
        assertEquals(0, BotFinalAttack.skillIdFor(BOWMAN_HUNTER, WeaponType.SWORD2H),
                "a bowman must never fire the warrior's 终极剑");
    }

    @Test
    void theAxeBluntSplitFollowsTheJobNotTheWeapon() {
        // GENERAL* covers both the fighter line's axes and the page line's blunt weapons.
        assertEquals(Fighter.FINAL_ATTACK_AXE, BotFinalAttack.skillIdFor(110, WeaponType.GENERAL2H_SWING));
        assertEquals(Fighter.FINAL_ATTACK_AXE, BotFinalAttack.skillIdFor(112, WeaponType.GENERAL1H_STAB));
        assertEquals(Page.FINAL_ATTACK_BW, BotFinalAttack.skillIdFor(120, WeaponType.GENERAL2H_SWING));
        assertEquals(Page.FINAL_ATTACK_BW, BotFinalAttack.skillIdFor(122, WeaponType.GENERAL1H_STAB));
        // And never for a non-warrior, whatever the weapon.
        assertEquals(0, BotFinalAttack.skillIdFor(THIEF, WeaponType.GENERAL2H_SWING));
    }

    @Test
    void spearAndPolearmBothResolveToTheSpearmanPassive() {
        for (WeaponType w : new WeaponType[]{WeaponType.SPEAR_STAB, WeaponType.SPEAR_SWING,
                WeaponType.POLE_ARM_SWING, WeaponType.POLE_ARM_STAB}) {
            assertEquals(Spearman.FINAL_ATTACK_POLEARM, BotFinalAttack.skillIdFor(130, w),
                    "spearman + " + w + " -> 终极矛");
            assertEquals(Spearman.FINAL_ATTACK_POLEARM, BotFinalAttack.skillIdFor(132, w));
        }
        assertEquals(0, BotFinalAttack.skillIdFor(THIEF, WeaponType.POLE_ARM_SWING));
    }

    @Test
    void bowAndCrossbowResolveToTheirOwnLinesPassives() {
        assertEquals(Hunter.FINAL_ATTACK, BotFinalAttack.skillIdFor(BOWMAN_HUNTER, WeaponType.BOW));
        assertEquals(Crossbowman.FINAL_ATTACK, BotFinalAttack.skillIdFor(BOWMAN_CROSSBOW, WeaponType.CROSSBOW));
        // A warrior never legitimately holds one; the mapping still answers (the caller's job gate
        // is what prunes it), so pin the value the caller's gate sees rather than a class refusal.
        assertEquals(Hunter.FINAL_ATTACK, BotFinalAttack.skillIdFor(112, WeaponType.BOW));
    }

    @Test
    void weaponsWithNoPassiveAndMissingInputsMapToNothing() {
        for (int job : new int[]{BEGINNER, THIEF, 200, 400, 410, 500, 520}) {
            assertEquals(0, BotFinalAttack.skillIdFor(job, WeaponType.SWORD2H),
                    "job " + job + " has no final attack");
            assertEquals(0, BotFinalAttack.skillIdFor(job, WeaponType.SPEAR_STAB));
        }
        assertEquals(0, BotFinalAttack.skillIdFor(112, WeaponType.CLAW));
        assertEquals(0, BotFinalAttack.skillIdFor(112, WeaponType.GUN));
        assertEquals(0, BotFinalAttack.skillIdFor(112, WeaponType.WAND));
        assertEquals(0, BotFinalAttack.skillIdFor(112, null));
    }
}
