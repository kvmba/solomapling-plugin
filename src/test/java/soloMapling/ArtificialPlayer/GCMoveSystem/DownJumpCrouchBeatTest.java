package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the crouch beat a CROUCH-sourced straight down-jump pays before takeoff.
 *
 * <p>Background: {@code queueDownJump} used to arm the pose and the very next grounded tick took off,
 * so the whole crouch was a single 50ms frame — a twitch, not the deliberate Down-then-Alt a player
 * performs. The beat is counted in TICKS (decremented by {@code tickMotionTimers} alongside
 * {@code downJumpGracePeriodMS}) rather than in wall-clock milliseconds, because a wall-clock hold
 * stalls a bot whose ticks run faster than real time: an execution sim driving the production tick
 * directly burned 30 ticks in a few milliseconds and never reached an 800ms deadline, which pinned the
 * bot on its launch row until the 8s no-progress give-up abandoned the whole descent.
 *
 * <p>The beat is opt-in: {@code queueDownJump} is the single entry point, so a caller that arms 0
 * ticks (the default) keeps the original next-tick takeoff.
 *
 * @see BotPhysicsEngine.Config#DOWN_JUMP_CROUCH_TICKS
 * @see BotMovementState#downJumpCrouchTicks
 */
class DownJumpCrouchBeatTest {

    private static BotMovementState state() {
        return new BotMovementState(null, null);
    }

    @Test
    @DisplayName("The default beat is zero, so a drop that never showed a crouch launches next tick")
    void theDefaultBeatIsZero() {
        assertEquals(0, state().downJumpCrouchTicks,
                "a fresh state must not hold a crouch - only the CROUCH ground action pays the beat");
    }

    @Test
    @DisplayName("One tickMotionTimers call spends exactly one beat tick")
    void oneTickSpendsOneBeatTick() {
        BotMovementState st = state();
        st.downJumpCrouchTicks = 4;
        BotPhysicsEngine.tickMotionTimers(st);
        assertEquals(3, st.downJumpCrouchTicks);
        BotPhysicsEngine.tickMotionTimers(st);
        BotPhysicsEngine.tickMotionTimers(st);
        BotPhysicsEngine.tickMotionTimers(st);
        assertEquals(0, st.downJumpCrouchTicks, "the beat must run out exactly, never go negative");
    }

    @Test
    @DisplayName("An exhausted beat never decrements below zero")
    void anExhaustedBeatNeverGoesNegative() {
        BotMovementState st = state();
        BotPhysicsEngine.tickMotionTimers(st);
        assertEquals(0, st.downJumpCrouchTicks);
        BotPhysicsEngine.tickMotionTimers(st);
        assertEquals(0, st.downJumpCrouchTicks,
                "ticking an exhausted beat must stay at zero so it can never read as 'still holding'");
    }

    @Test
    @DisplayName("The configured beat is long enough to read as a deliberate crouch")
    void theConfiguredBeatIsDeliberate() {
        int ticks = BotPhysicsEngine.cfg.DOWN_JUMP_CROUCH_TICKS;
        long ms = (long) ticks * BotPhysicsEngine.cfg.TICK_MS;
        assertTrue(ticks > 1,
                "a one-frame crouch is the twitch this beat exists to replace");
        assertTrue(ms >= 500,
                "a crouch must be visible for at least half a second; GCFidget's own DUCK_MIN_MS is 900");
        assertTrue(ms < 5_000,
                "the beat must stay far below the 8s no-progress give-up, or a stalled drop is abandoned");
    }

    @Test
    @DisplayName("A deliberate hold is exempt from the stuck watchdog, so it is never rescue-hopped")
    void aDeliberateHoldIsExemptFromTheStuckWatchdog() {
        // The hold itself raises the flag; this pins the watchdog's half of the contract, so a future
        // edit to isStuckCheckExempt cannot silently re-open the "hop, re-land, hop" loop the
        // drop-cadence hold already hit (BotNavigationManager.tryExecuteDrop).
        // The comparison case must carry a goal, otherwise the (navEdge == null && moveTarget == null)
        // clause exempts it anyway and the flag's own contribution would be invisible.
        BotMovementState holding = state();
        holding.launchReadyAwaiting = true;
        holding.moveTarget = new java.awt.Point(10, 10);
        assertTrue(BotMovementManager.isStuckCheckExempt(holding),
                "a bot deliberately holding its launch must not be read as wedged");

        BotMovementState waiting = state();
        waiting.moveTarget = new java.awt.Point(10, 10);
        assertFalse(BotMovementManager.isStuckCheckExempt(waiting),
                "without the hold flag the same goal-carrying bot is NOT exempt - the flag is what "
                        + "protects the beat, and dropping it re-opens the rescue-hop loop");
    }
}
