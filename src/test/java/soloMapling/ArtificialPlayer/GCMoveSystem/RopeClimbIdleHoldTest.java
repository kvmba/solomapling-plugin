package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the "bot at a rope top wriggles up-down for seconds, then is
 * re-sided" report (LPQ stage 1's top-left pocket, and every stacked map that aims a
 * precise climb anchor near a rope top).
 *
 * <p>{@code shouldHoldClimbIdle} held any non-grind, non-edge climber whose goal sat
 * within STOP_DIST (30px) of it. A precise CLIMB anchor (navPreciseTarget=true,
 * targetX == rope.x) routinely sits inside that band while the bot is still one climb
 * step (~5px) or more away, so the hold parked the bot off-anchor with the move held
 * open; the 2s airborne stall give-up then dropped the move, the quest layer re-seeked,
 * the same anchor was re-issued — hold at -3050 vs anchor -3053, advance once to -3055,
 * hold again: the up-down wriggle. Releasing the hold for precise targets lets
 * tickClimbing's directional branch advance (applyClimbAction's CLIMB_UP with |dy|=3
 * lands exactly on the anchor via resolveClimbBoundary, and shouldSnapToClimbTarget
 * settles any sub-step remainder), so a precise anchor is always reached.
 *
 * <p>Imprecise holds are pinned unchanged: the rope rest hold wins over everything, and
 * an ordinary follow/grind stop inside the band still hangs instead of twitching.
 */
class RopeClimbIdleHoldTest {

    private static BotMovementState entry(boolean precise, boolean resting) {
        BotMovementState e = new BotMovementState(null, null);
        e.climbing = true;
        e.navPreciseTarget = precise;
        e.resting = resting;
        return e;
    }

    @Test
    void aPreciseAnchorWithinTheHoldBandIsStillWalkedTo() {
        // Anchor 3px above a climber parked off it (LPQ stage-1 numbers: anchor -3053, bot -3050).
        assertFalse(shouldHoldClimbIdle(entry(true, false), -3, 0),
                "a precise anchor inside the hold band must be advanced to, not held short of");
    }

    @Test
    void aPreciseAnchorFarAwayIsStillWalkedTo() {
        assertFalse(shouldHoldClimbIdle(entry(true, false), -200, 0),
                "precise targets keep steering regardless of distance");
    }

    @Test
    void anImpreciseFollowStopInsideTheBandStillHangs() {
        // dy/dxOwner within STOP_DIST / FOLLOW_DIST*2, no precise flag: the organic rope pause.
        assertTrue(shouldHoldClimbIdle(entry(false, false), -10, 40));
    }

    @Test
    void aRopeRestHoldWinsOverThePreciseRelease() {
        // A rest hang must survive: the brain froze it deliberately, and a rest spot is never
        // a precise climb anchor, so ordering resting above the precise release is safe.
        assertTrue(shouldHoldClimbIdle(entry(true, true), -3, 0),
                "an explicit rope rest hold must not be dislodged by the precise-target release");
    }

    @Test
    void aCommittedEdgeStillOwnsTheClimber() {
        BotMovementState e = entry(true, false);
        e.navEdge = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.CLIMB,
                new java.awt.Point(0, 0), new java.awt.Point(0, 0),
                0, -1, 0, 0, 0, 100);
        assertFalse(shouldHoldClimbIdle(e, -3, 0));
    }

    private static boolean shouldHoldClimbIdle(BotMovementState entry, int dy, int dxOwner) {
        return BotMovementManager.shouldHoldClimbIdle(entry, dy, dxOwner);
    }
}
