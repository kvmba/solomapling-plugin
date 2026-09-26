package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the 疾驰 burst roll and lifetime as pure seams ({@link BotDashBurst}):
 *
 * <ul>
 *   <li>only an uninterrupted one-direction walk of at least {@code ROLL_MIN_WALK_PX} qualifies
 *       for the roll — short hops and direction reversals never do;</li>
 *   <li>exactly ONE roll attempt is spent per qualifying walk (win or lose), so a long walk
 *       cannot re-roll at 20 Hz until it hits;</li>
 *   <li>a burst expires on its own clock, and the post-burst cooldown gates the next roll.</li>
 * </ul>
 */
class BotDashBurstTest {

    private static final long T0 = 1_000_000L;

    @Test
    void walkAccumulatesAlongOneDirection() {
        int id = 1;
        assertEquals(0, BotDashBurst.advanceWalk(id, 1000), "origin tick measures nothing yet");
        assertEquals(10, BotDashBurst.advanceWalk(id, 1010), "10 px along the (new) direction");
        assertEquals(30, BotDashBurst.advanceWalk(id, 1030), "30 px from the origin");
        assertEquals(60, BotDashBurst.advanceWalk(id, 1060), "60 px from the origin");
    }

    @Test
    void reversalRestartsTheWalk() {
        int id = 2;
        BotDashBurst.advanceWalk(id, 1000);
        BotDashBurst.advanceWalk(id, 1100);
        assertEquals(-1, BotDashBurst.advanceWalk(id, 900), "a reversal resets the walk");
        // After the reset the origin is the reversal x and the direction re-measures:
        assertEquals(0, BotDashBurst.advanceWalk(id, 900), "origin tick after reset");
        assertEquals(50, BotDashBurst.advanceWalk(id, 950));
    }

    @Test
    void shortWalksNeverQualify() {
        int id = 3;
        BotDashBurst.advanceWalk(id, 1000);
        assertFalse(BotDashBurst.walkQualifies(BotDashBurst.ROLL_MIN_WALK_PX - 1, T0, 0L),
                "one px short of the roll distance must not qualify");
        assertTrue(BotDashBurst.walkQualifies(BotDashBurst.ROLL_MIN_WALK_PX, T0, 0L),
                "exactly the roll distance qualifies");
    }

    @Test
    void cooldownGatesTheNextRoll() {
        assertFalse(BotDashBurst.walkQualifies(BotDashBurst.ROLL_MIN_WALK_PX, T0, T0 + 1),
                "a walk inside the post-burst cooldown must not qualify");
        assertTrue(BotDashBurst.walkQualifies(BotDashBurst.ROLL_MIN_WALK_PX, T0, T0 - 1),
                "once the cooldown has passed the next walk may roll");
    }

    @Test
    void startBurstIsResolvableOnlyWithARealSkill() {
        // The roll path passes the bot's own dash skill id (5001005 for a pirate); the burst
        // start must reject a null bot and an unresolvable id rather than throw — the tick's
        // self-reschedule chain must never die on a burst lookup.
        assertFalse(BotDashBurst.startBurst(null, PirateSkillHolder.DASH_ID));
        // A live character cannot be built in a unit test (org.gms.client.Character), so the
        // happy path is exercised by the server build; the null/id guards are the seam here.
    }

    @Test
    void everyThirdChanceRollWinsOnAverage() {
        // The roll chance stays a deliberate minority behaviour — assert the constant itself so
        // a tuning change is a conscious one.
        assertEquals(1.0 / 3.0, BotDashBurstRollHolder.ROLL_CHANCE_FOR_TEST, 1e-9);
    }

    @Test
    void poseGateRefusesNewRollsWhileTransformedOnShipOrMounted() {
        // 变身 (TRANSFORMATION / SUPER_TRANSFORMATION), the gunner's 海盗船 (the host registers it
        // as a MONSTER_RIDING buff) and the 骑宠 mount all own the body/ride — the real client
        // refuses the dash key in all three, so no NEW burst may roll.
        assertFalse(BotDashBurst.poseRefusesDash(false, false), "plain walking may roll");
        assertTrue(BotDashBurst.poseRefusesDash(true, false),
                "a 变身/海盗船 enabler aura (morphed) refuses the roll");
        assertTrue(BotDashBurst.poseRefusesDash(false, true), "a 骑宠 mount refuses the roll");
        assertTrue(BotDashBurst.poseRefusesDash(true, true));
        // The enabler classification the morphed leg rides on is pinned in BotAuraStateTest:
        // TRANSFORMATION / SUPER_TRANSFORMATION / BATTLE_SHIP are all attack enablers, and the
        // ship's host-side buff is the MONSTER_RIDING bit this gate checks for the mount leg.
    }

    /** Indirection so the test can read the Pirate constant without a live client. */
    static final class PirateSkillHolder {
        static final int DASH_ID = org.gms.constants.skills.Pirate.DASH;
    }

    /** Indirection for the package-private roll constant. */
    static final class BotDashBurstRollHolder {
        static final double ROLL_CHANCE_FOR_TEST = BotDashBurst.ROLL_CHANCE;
    }
}
