package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the 疾驰 burst lifecycle as pure seams ({@link BotDashBurst}):
 *
 * <ul>
 *   <li>only an uninterrupted one-direction walk of at least {@code ROLL_MIN_WALK_PX} qualifies
 *       for the roll — short hops and direction reversals never do;</li>
 *   <li>exactly ONE roll attempt is spent per qualifying walk (win or lose), so a long walk
 *       cannot re-roll at 20 Hz until it hits;</li>
 *   <li>a burst expires on its own clock, AND is released the moment the bot stops moving on the
 *       ground — the same tick, with the post-burst cooldown armed either way;</li>
 *   <li>the pose gate (变身 / 海盗船 / 骑宠) refuses NEW rolls and kills the accumulating walk.</li>
 * </ul>
 */
class BotDashBurstTest {

    private static final long T0 = 1_000_000L;
    private static final int ID = 77;          // a test bot id (maps are cleared after each test)
    private static final int SKILL = org.gms.constants.skills.Pirate.DASH;

    @AfterEach
    void cleanState() {
        BotDashBurst.clearBot(ID);
    }

    // ── stop-release (the "bot stopped moving ⇒ cancel now" rule) ────────────────

    @Test
    void aStoppedTickReleasesTheBurstImmediately() {
        // Seed a live burst the way startBurst would (a WZ-resolvable skill id is not needed for
        // the lifecycle seams).
        BotDashBurst.BURST_UNTIL.put(ID, T0 + 20_000L);
        BotDashBurst.SPEED_BONUS.put(ID, 30);
        BotDashBurst.WALK.put(ID, new BotDashBurst.Walk(1000, 1));

        BotDashBurst.tickBurst(ID, SKILL, false, false, 1100, T0, true);

        assertFalse(BotDashBurst.BURST_UNTIL.containsKey(ID), "the burst is gone the same tick");
        assertFalse(BotDashBurst.SPEED_BONUS.containsKey(ID), "the physics bonus is gone");
        assertFalse(BotDashBurst.WALK.containsKey(ID), "the walk accumulator died with the stop");
        assertEquals(T0 + BotDashBurst.ROLL_COOLDOWN_MS, BotDashBurst.NEXT_ROLL_AT.get(ID),
                "the post-burst cooldown is armed");
    }

    @Test
    void stopReleaseBeforeExpiryArmsTheSameCooldownAsExpiry() {
        // Stopping early must not bypass the cooldown: the next roll is gated exactly like an
        // expiry would gate it.
        BotDashBurst.BURST_UNTIL.put(ID, T0 + 20_000L);
        BotDashBurst.NEXT_ROLL_AT.put(ID, T0 + 28_000L); // as startBurst set it (20s + 8s)

        BotDashBurst.tickBurst(ID, SKILL, false, false, 1000, T0 + 5_000L, true);

        assertEquals(T0 + 5_000L + BotDashBurst.ROLL_COOLDOWN_MS, BotDashBurst.NEXT_ROLL_AT.get(ID),
                "the cooldown re-arms from the STOP time, not the original schedule");
    }

    @Test
    void aMovingTickKeepsTheBurstAlive() {
        BotDashBurst.BURST_UNTIL.put(ID, T0 + 20_000L);
        BotDashBurst.SPEED_BONUS.put(ID, 30);

        BotDashBurst.tickBurst(ID, SKILL, false, false, 1100, T0, false);

        assertTrue(BotDashBurst.BURST_UNTIL.containsKey(ID), "moving keeps the burst");
        assertEquals(30, BotDashBurst.SPEED_BONUS.get(ID), "the bonus rides");
    }

    @Test
    void anExpiredTickReleasesEvenWithoutAStop() {
        // A burst whose timer runs out while the bot keeps moving (it can't, physically — but the
        // guard must not depend on that) releases on the clock alone.
        BotDashBurst.BURST_UNTIL.put(ID, T0 - 1);
        BotDashBurst.SPEED_BONUS.put(ID, 30);

        BotDashBurst.tickBurst(ID, SKILL, false, false, 1100, T0, false);

        assertFalse(BotDashBurst.BURST_UNTIL.containsKey(ID), "expiry releases the burst");
        assertEquals(T0 + BotDashBurst.ROLL_COOLDOWN_MS, BotDashBurst.NEXT_ROLL_AT.get(ID));
    }

    // ── pose gate ────────────────────────────────────────────────────────────────

    @Test
    void poseGateRefusesNewRollsWhileTransformedOnShipOrMounted() {
        assertFalse(BotDashBurst.poseRefusesDash(false, false), "plain walking may roll");
        assertTrue(BotDashBurst.poseRefusesDash(true, false),
                "a 变身/海盗船 enabler aura (morphed) refuses the roll");
        assertTrue(BotDashBurst.poseRefusesDash(false, true), "a 骑宠 mount refuses the roll");
        assertTrue(BotDashBurst.poseRefusesDash(true, true));
        // The enabler classification the morphed leg rides on is pinned in BotAuraStateTest:
        // TRANSFORMATION / SUPER_TRANSFORMATION / BATTLE_SHIP are all attack enablers, and the
        // ship's host-side buff is the MONSTER_RIDING bit this gate checks for the mount leg.
    }

    @Test
    void aPoseChangeKillsTheAccumulatingWalk() {
        BotDashBurst.WALK.put(ID, new BotDashBurst.Walk(1000, 1));

        BotDashBurst.tickBurst(ID, SKILL, true, false, 1100, T0, false);

        assertFalse(BotDashBurst.WALK.containsKey(ID), "a pose change kills the walk");
    }

    // ── roll qualification (unchanged rules, re-pinned through the core) ─────────

    @Test
    void walkAccumulatesAlongOneDirection() {
        assertEquals(0, BotDashBurst.advanceWalk(ID, 1000), "origin tick measures nothing yet");
        assertEquals(10, BotDashBurst.advanceWalk(ID, 1010), "10 px along the (new) direction");
        assertEquals(30, BotDashBurst.advanceWalk(ID, 1030), "30 px from the origin");
        assertEquals(60, BotDashBurst.advanceWalk(ID, 1060), "60 px from the origin");
    }

    @Test
    void reversalRestartsTheWalk() {
        BotDashBurst.advanceWalk(ID, 1000);
        BotDashBurst.advanceWalk(ID, 1100);
        assertEquals(-1, BotDashBurst.advanceWalk(ID, 900), "a reversal resets the walk");
        // After the reset the origin is the reversal x and the direction re-measures:
        assertEquals(0, BotDashBurst.advanceWalk(ID, 900), "origin tick after reset");
        assertEquals(50, BotDashBurst.advanceWalk(ID, 950));
    }

    @Test
    void shortWalksNeverQualify() {
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
    void everyThirdChanceRollWinsOnAverage() {
        // The roll chance stays a deliberate minority behaviour — assert the constant itself so
        // a tuning change is a conscious one.
        assertEquals(1.0 / 3.0, BotDashBurst.ROLL_CHANCE, 1e-9);
    }

    @Test
    void startBurstIsResolvableOnlyWithARealSkill() {
        // The burst start must reject an unresolvable id rather than throw — the tick's
        // self-reschedule chain must never die on a burst lookup. (A live Character cannot be
        // built in a unit test, so the happy path is exercised by the server build.)
        assertFalse(BotDashBurst.startBurst((Character) null, SKILL));
        assertFalse(BotDashBurst.startBurst(ID, 0));
    }
}
