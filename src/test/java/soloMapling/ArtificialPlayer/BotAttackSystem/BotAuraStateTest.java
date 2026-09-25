package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.constants.game.CharacterStance;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.NightWalker;
import org.gms.constants.skills.Pirate;
import org.gms.constants.skills.Rogue;
import org.gms.constants.skills.ThunderBreaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the state-bound aura rules of {@link BotAuraState} as pure seams (no live bot):
 *
 * <ul>
 *   <li>疾驰 holds only while the wire stance is a walk - it must drop on stand / jump / swim / rope
 *       / ladder, and never on a turn (walk-left is still a walk).</li>
 *   <li>only 橡木伪装 counts as the censorable disguise; the attackable 变身 morphs and the 疾驰
 *       family must classify correctly so an attack cancels the former and never the latter.</li>
 *   <li>the hide family (橡木伪装 + 隐身术) is exactly the pair whose presence makes the bot
 *       unattackable - a 变身 never does.</li>
 * </ul>
 */
class BotAuraStateTest {

    @Test
    void dashHoldsOnlyWhileWalking() {
        assertTrue(BotAuraState.dashHolds(CharacterStance.WALK_RIGHT_STANCE),
                "walking right keeps 疾驰 up");
        assertTrue(BotAuraState.dashHolds(CharacterStance.WALK_LEFT_STANCE),
                "walking left keeps 疾驰 up (a turn is still a walk)");
        assertFalse(BotAuraState.dashHolds(CharacterStance.STAND_RIGHT_STANCE),
                "standing must cancel 疾驰");
        assertFalse(BotAuraState.dashHolds(CharacterStance.STAND_LEFT_STANCE));
        assertFalse(BotAuraState.dashHolds(CharacterStance.JUMP_RIGHT_STANCE),
                "a jump must cancel 疾驰");
        assertFalse(BotAuraState.dashHolds(CharacterStance.SWIM_RIGHT_STANCE),
                "swimming must cancel 疾驰");
        assertFalse(BotAuraState.dashHolds(CharacterStance.ROPE_RIGHT_STANCE),
                "a rope must cancel 疾驰");
        assertFalse(BotAuraState.dashHolds(CharacterStance.LADDER_RIGHT_STANCE),
                "a ladder must cancel 疾驰");
    }

    @Test
    void dashFamilyIsRecognised() {
        assertTrue(BotAuraState.isDash(Pirate.DASH));
        assertTrue(BotAuraState.isDash(ThunderBreaker.DASH));
        assertFalse(BotAuraState.isDash(Marauder.TRANSFORMATION));
        assertFalse(BotAuraState.isDash(Brawler.OAK_BARREL));
    }

    @Test
    void onlyOakBarrelIsTheCensorableDisguise() {
        assertTrue(BotAuraState.isDisguise(Brawler.OAK_BARREL),
                "橡木伪装 is the hide morph an attack cancels");
        assertFalse(BotAuraState.isDisguise(Marauder.TRANSFORMATION),
                "变身 is attackable - it must not be cancelled by an attack");
        assertFalse(BotAuraState.isDisguise(Pirate.DASH));
    }

    @Test
    void transformMorphsAreAttackableAndDistinctFromTheDisguise() {
        assertTrue(BotAuraState.isTransformMorph(Marauder.TRANSFORMATION));
        assertTrue(BotAuraState.isTransformMorph(ThunderBreaker.TRANSFORMATION));
        assertFalse(BotAuraState.isTransformMorph(Brawler.OAK_BARREL),
                "the hide morph is not a transform");
    }

    @Test
    void darkSightFamilyIsRecognised() {
        assertTrue(BotAuraState.isDarkSight(Rogue.DARK_SIGHT),
                "explorer 隐身术 (4001003) is the thief hide");
        assertTrue(BotAuraState.isDarkSight(NightWalker.DARK_SIGHT),
                "night walker 隐身术 (14001003) mirrors the host's own isDs set");
        assertFalse(BotAuraState.isDarkSight(Brawler.OAK_BARREL));
        assertFalse(BotAuraState.isDarkSight(Pirate.DASH));
    }

    @Test
    void theHideFamilyIsExactlyOakBarrelPlusDarkSight() {
        // The two auras that make a bot unattackable and that every attack / mount cancels.
        assertTrue(BotAuraState.isHide(Brawler.OAK_BARREL));
        assertTrue(BotAuraState.isHide(Rogue.DARK_SIGHT));
        assertTrue(BotAuraState.isHide(NightWalker.DARK_SIGHT));
        assertFalse(BotAuraState.isHide(Marauder.TRANSFORMATION),
                "变身 is attackable - it never hides the bot or drops on an attack");
        assertFalse(BotAuraState.isHide(Pirate.DASH),
                "疾驰 is stance-bound, not a hide");
    }
}
