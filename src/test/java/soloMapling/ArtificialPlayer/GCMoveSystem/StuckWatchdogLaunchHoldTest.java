package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the town-bot "bounces in place at the ledge and never jumps to the platform
 * beside it" report.
 *
 * <p>When a committed JUMP edge's POSITION GATE is satisfied but the launch is withheld for a transient
 * reason - the exact-profile nav graph is still baking ({@code jump-graph-warmup}), the bot is walking a
 * step deeper into the window ({@code jump-delay}), or residual ground slide is being shed
 * ({@code jump-slide}) - the bot is exactly where it must be. The stuck watchdog did not know that:
 * after ~500ms of standing still it fired {@code tickUnstuck}, which hops the bot in a RANDOM direction
 * AND clears the nav edge - throwing away a satisfied launch window, so the bot pogoes beside the ledge
 * forever instead of ever taking the jump.
 *
 * <p>{@code tryExecuteJump} raises {@link BotMovementState#launchReadyAwaiting} for exactly those three
 * holds, and {@link BotMovementManager#isStuckCheckExempt} makes the watchdog stand down while it is set.
 * The flag is decided once per navigation tick (cleared at the top of
 * {@code BotNavigationManager.resolveTarget}), so it can never outlive the reason it was raised for.
 *
 * <p>These tests pin the exempt truth table directly, including that a genuinely idle bot is STILL
 * exempt (it has nothing to do, so hopping would be wrong too) while a bot holding a real
 * {@code moveTarget} with no committed edge remains rescuable.
 */
class StuckWatchdogLaunchHoldTest {

    private static BotMovementState state() {
        return new BotMovementState(null, null);
    }

    private static BotNavigationGraph.Edge jumpEdge() {
        return new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                new java.awt.Point(100, 0), new java.awt.Point(300, -50),
                100, 120, 6, 0, 0, 0, 0, 500);
    }

    @Test
    void aLaunchHoldIsExempt() {
        BotMovementState st = state();
        st.navEdge = jumpEdge();
        st.launchReadyAwaiting = true;
        assertTrue(BotMovementManager.isStuckCheckExempt(st),
                "a bot holding a satisfied launch must not be rescue-hopped off the window");
    }

    @Test
    void anAirborneOrClimbingOrGraphWarmupBotIsExempt() {
        BotMovementState air = state();
        air.navEdge = jumpEdge();
        air.inAir = true;
        assertTrue(BotMovementManager.isStuckCheckExempt(air));

        BotMovementState climb = state();
        climb.navEdge = jumpEdge();
        climb.climbing = true;
        assertTrue(BotMovementManager.isStuckCheckExempt(climb));

        BotMovementState warm = state();
        warm.navEdge = jumpEdge();
        warm.graphWarmupFallback = true;
        assertTrue(BotMovementManager.isStuckCheckExempt(warm));
    }

    @Test
    void anIdleBotWithNoGoalIsExempt() {
        // No nav edge and no move target: nothing to be stuck ON - hopping it would be wrong too.
        assertTrue(BotMovementManager.isStuckCheckExempt(state()));
    }

    @Test
    void aGroundedBotWithAnEdgeButNoHoldIsNotExempt() {
        // The bot really is parked against an edge with no transient excuse: the watchdog must stay free
        // to rescue it (otherwise a genuinely blocked bot would hang forever).
        BotMovementState st = state();
        st.navEdge = jumpEdge();
        st.launchReadyAwaiting = false;
        assertFalse(BotMovementManager.isStuckCheckExempt(st));
    }

    @Test
    void aGroundedBotWithOnlyAMoveTargetIsNotExempt() {
        // A move() with no committed edge yet is a real stall candidate (e.g. an unreachable target).
        BotMovementState st = state();
        st.moveTarget = new java.awt.Point(0, 0);
        st.launchReadyAwaiting = false;
        assertFalse(BotMovementManager.isStuckCheckExempt(st));
    }

    @Test
    void clearingNavigationDropsTheHold() {
        // The hold must not survive the edge it was raised for: a stale true would blind the watchdog
        // for the rest of the session (only the JUMP dispatch path ever clears it).
        BotMovementState st = state();
        st.navEdge = jumpEdge();
        st.launchReadyAwaiting = true;
        BotMovementManager.clearNavigationState(st);
        assertFalse(st.launchReadyAwaiting, "clearNavigationState must drop the launch hold");
    }
}
