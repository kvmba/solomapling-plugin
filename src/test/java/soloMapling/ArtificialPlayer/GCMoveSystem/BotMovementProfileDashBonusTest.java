package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Guards the 疾驰 burst's step-profile builder ({@link BotMovementProfile#withDashSpeedBonus}):
 *
 * <ul>
 *   <li>the WZ speed bonus is added as a STAT (not a factor) and stays EXACT — not bucketed — so
 *       a +30 burst on a 105-speed pirate really walks 135;</li>
 *   <li>a zero/negative bonus is a no-op that returns the same instance;</li>
 *   <li>the canonical caps still clamp the result (a 200-speed pirate bursting to 230 walks 200).</li>
 * </ul>
 */
class BotMovementProfileDashBonusTest {

    @Test
    void dashBonusAddsTheStatExactly() {
        BotMovementProfile pirate = new BotMovementProfile(105, 110);
        BotMovementProfile dashing = pirate.withDashSpeedBonus(30);
        assertEquals(135, dashing.totalSpeedStat(), "+30 speed = the WZ max-level burst");
        assertEquals(110, dashing.totalJumpStat(), "the jump stat is untouched (the impulse stays graph-validated)");
    }

    @Test
    void dashBonusIsNotBucketed() {
        // 105 + 30 = 135 is not a multiple of the 5-stat bucket — it must survive exactly, the
        // same way a follower's reduced stat does: the burst profile never feeds the graph key.
        assertEquals(136, new BotMovementProfile(106, 100).withDashSpeedBonus(30).totalSpeedStat());
    }

    @Test
    void zeroOrNegativeBonusIsANoOp() {
        BotMovementProfile pirate = new BotMovementProfile(105, 110);
        assertSame(pirate, pirate.withDashSpeedBonus(0), "no burst = the identical profile");
        assertSame(pirate, pirate.withDashSpeedBonus(-5));
    }

    @Test
    void theCanonicalCapStillClamps() {
        // A maxed equip pirate (200 = MAX_EFFECTIVE_SPEED_STAT) bursting to 230 must clamp back
        // to the physics cap — the same ceiling every other speed source respects.
        assertEquals(BotMovementProfile.MAX_EFFECTIVE_SPEED_STAT,
                new BotMovementProfile(BotMovementProfile.MAX_EFFECTIVE_SPEED_STAT, 100)
                        .withDashSpeedBonus(30).totalSpeedStat());
    }
}
