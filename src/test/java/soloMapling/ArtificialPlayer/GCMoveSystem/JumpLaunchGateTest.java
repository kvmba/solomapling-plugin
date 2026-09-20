package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the JUMP launch gate — the "grinder paces left-right on a small platform and
 * never jumps out" report.
 *
 * <p>{@code isWithinJumpLaunchWindow} must accept a bot that is within one walk step of the launch
 * window, because that is exactly the phase the executor fires within
 * ({@code canExecuteSelectedJumpFromCurrentPosition} tolerates {@code |botX - launchX| <= walkStep})
 * and the phase the graph builder already insets the window by ({@code jumpLaunchMargin}). A
 * zero-tolerance membership test rejects a bot parked one pixel short of the window — and jump
 * approaches run at stopDist 0, so the release-on-momentum rule in {@code updateStepX} is skipped
 * and the bot strides full walkStep steps straight across an inset-collapsed single-pixel window,
 * overshoots, flips back, and paces forever without ever launching.
 *
 * <p>A real {@code MapleMap}/{@code Character} cannot be built in a unit test (Spring static
 * initializer); {@code isWithinJumpLaunchWindow} draws only on the graph, the edge, and the bot's
 * point, all of which are constructible from plain footholds — this pins the production gate
 * directly.
 */
class JumpLaunchGateTest {

    private static final BotMovementProfile PROFILE = BotMovementProfile.base();

    /** One flat ground region spanning [minX,maxX] at y, as the graph builder would make it. */
    private static BotNavigationGraph.Region region(int id, int minX, int maxX, int y) {
        Foothold fh = new Foothold(new Point(minX, y), new Point(maxX, y), id);
        return new BotNavigationGraph.Region(id, List.of(new BotNavigationGraph.Segment(fh, false)));
    }

    /** A JUMP edge out of region 1 whose launch window is [launchMinX, launchMaxX]. */
    private static BotNavigationGraph.Edge jumpEdge(int launchMinX, int launchMaxX) {
        int step = BotPhysicsEngine.walkStep(null, PROFILE);
        return new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(launchMinX, 0), new Point(launchMaxX + 200, -50),
                launchMinX, launchMaxX, step, 0, 0, 0, 0, 500);
    }

    /** A graph whose region 1 is the flat ledge [-200,200] at y=0. */
    private static BotNavigationGraph graph() {
        BotNavigationGraph.Region r1 = region(1, -200, 200, 0);
        List<BotNavigationGraph.Region> regions = List.of(r1);
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        byId.put(1, r1);
        return new BotNavigationGraph(1000000, 63, PROFILE, regions, byId,
                Map.of(), Map.of(), Set.of(), Set.of());
    }

    @Test
    void acceptsABotWithinOneWalkStepOfACollapsedSinglePixelWindow() {
        // The inset collapsed a thin window to one pixel (launchMinX == launchMaxX). A bot one walk
        // step short of it must still be allowed to fire — this is the exact case that paced.
        int step = BotPhysicsEngine.walkStep(null, PROFILE);
        BotNavigationGraph g = graph();
        BotNavigationGraph.Edge edge = jumpEdge(100, 100);
        assertTrue(BotNavigationManager.isWithinJumpLaunchWindow(g, new Point(100 - step, 0), edge),
                "a bot one walk step short of the launch x must be within the window");
    }

    @Test
    void stillRejectsABotFurtherThanOneWalkStep() {
        // The tolerance is bounded, not unbounded: firing from arbitrary distance would re-introduce
        // the overfly/fall it was added to prevent.
        int step = BotPhysicsEngine.walkStep(null, PROFILE);
        BotNavigationGraph g = graph();
        BotNavigationGraph.Edge edge = jumpEdge(100, 100);
        assertFalse(BotNavigationManager.isWithinJumpLaunchWindow(g, new Point(100 - step - 1, 0), edge),
                "a bot more than one walk step away is not in the launch window");
        assertFalse(BotNavigationManager.isWithinJumpLaunchWindow(g, new Point(100 + step + 1, 0), edge),
                "the gate is symmetric");
    }

    @Test
    void aWideWindowStillAcceptsItsInteriorAndNeighbourhood() {
        BotNavigationGraph g = graph();
        BotNavigationGraph.Edge edge = jumpEdge(60, 100);
        assertTrue(BotNavigationManager.isWithinJumpLaunchWindow(g, new Point(80, 0), edge),
                "an in-window launch x is always accepted");
        assertTrue(BotNavigationManager.isWithinJumpLaunchWindow(g, new Point(60 - 3, 0), edge),
                "just outside the near end is inside the launch phase");
    }

    @Test
    void theVerticalGateIsUnchanged() {
        // Widening the X membership must not weaken the Y gate: a bot at the right X but the wrong
        // height is still rejected.
        BotNavigationGraph g = graph();
        BotNavigationGraph.Edge edge = jumpEdge(100, 100);
        assertFalse(BotNavigationManager.isWithinJumpLaunchWindow(g, new Point(100, 100), edge),
                "a bot far off the ledge's own Y is still not a valid launch");
    }
}
