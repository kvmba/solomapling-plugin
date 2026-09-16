package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Guards that an unobserved bot's analytic position is always a point a player could believe, because
 * that position is what MapleMap spawns the bot at the instant a real player arrives (and what the
 * promoted physics then continues from). Only a WALK edge lies along one walkable surface, so only a
 * WALK edge may be interpolated; every other edge (JUMP / DROP / CLIMB / PORTAL) crosses empty space,
 * so a straight lerp would float the bot in mid-air and make it visibly drop/snap when seen. Those
 * must quantise to a real standing point.
 */
class MovementPlanGroundPointTest {

    private static BotNavigationGraph.Edge edge(BotNavigationGraph.EdgeType type, Point start, Point end) {
        return new BotNavigationGraph.Edge(0, 1, type, start, end, 0, 0, 0, 0, 0, 0, 0, 100);
    }

    private static MovementPlan plan(BotNavigationGraph.EdgeType type, Point start, Point end) {
        return MovementPlan.inMap(7, List.of(edge(type, start, end)));
    }

    @Test
    void walkEdgeInterpolatesAlongTheGround() {
        MovementPlan p = plan(BotNavigationGraph.EdgeType.WALK, new Point(0, 100), new Point(100, 100));
        assertEquals(new Point(50, 100), p.positionAt(50));
    }

    @Test
    void jumpEdgeNeverFloatsBetweenTheLedges() {
        // Two ledges at different heights: the mid-edge point must be a ledge, not the empty air a
        // straight lerp would put at (50, 75).
        MovementPlan p = plan(BotNavigationGraph.EdgeType.JUMP, new Point(0, 100), new Point(100, 50));
        assertEquals(new Point(0, 100), p.positionAt(50), "holds the launch ledge until the edge ends");
        assertNotEquals(new Point(50, 75), p.positionAt(50));
    }

    @Test
    void dropAndPortalEdgesAlsoHoldALedge() {
        MovementPlan drop = plan(BotNavigationGraph.EdgeType.DROP, new Point(0, 100), new Point(100, 40));
        assertEquals(new Point(0, 100), drop.positionAt(50));
        MovementPlan portal = plan(BotNavigationGraph.EdgeType.PORTAL, new Point(0, 100), new Point(100, 40));
        assertEquals(new Point(0, 100), portal.positionAt(50));
    }

    @Test
    void jumpGrabOnRopeHoldsALaunchPointNotMidAir() {
        // A jump-to-grab edge is typed CLIMB but runs from a ground launch point to a rope point at a
        // DIFFERENT x: it must quantise, never lerp onto the empty line between them.
        MovementPlan p = plan(BotNavigationGraph.EdgeType.CLIMB, new Point(10, 300), new Point(210, 120));
        assertEquals(new Point(10, 300), p.positionAt(50), "holds the launch point");
        assertNotEquals(new Point(110, 210), p.positionAt(50));
    }

    @Test
    void climbUpRopeHoldsTheStartAnchor() {
        // A vertical climb (start and end on the same rope, same x): still quantises — the bot holds
        // the rope anchor it grabbed, never lerping up the rope unseen.
        MovementPlan p = plan(BotNavigationGraph.EdgeType.CLIMB, new Point(200, 300), new Point(200, 100));
        assertEquals(new Point(200, 300), p.positionAt(50));
        assertNotEquals(new Point(200, 200), p.positionAt(50));
    }

    @Test
    void endpointsAreExactRegardlessOfType() {
        MovementPlan p = plan(BotNavigationGraph.EdgeType.JUMP, new Point(0, 100), new Point(100, 50));
        assertEquals(new Point(0, 100), p.positionAt(0));
        assertEquals(new Point(100, 50), p.positionAt(100));
    }
}
