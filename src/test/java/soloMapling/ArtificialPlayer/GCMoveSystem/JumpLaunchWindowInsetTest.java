package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the jump launch-window inset (the "bot jumps to another platform, misses the
 * edge, and plunges to the bottom" report).
 *
 * <p>{@code BotNavigationGraphProvider.jumpLaunchMargin} insets a JUMP edge's launch window by one
 * walk step before it is stamped onto the edge. The navigator fires the jump the instant the bot is
 * within one walk step of the selected launch x and the deepen pass walks a further step into the
 * window, so without the inset a takeoff pixel can sit a whole walk step past the validated window —
 * on a small platform whose arc only just clears its edge, that overflies the target and the bot
 * falls. Measured on the Orbis Tower floors, most windows reached their source ledge's very edge
 * (0px gap) and ~6.5% of same-or-higher-platform jumps missed; the inset removed ~80% of those
 * misses with no loss of region reachability.
 *
 * <p>{@code insetJumpLaunchWindow} is a pure function of (region, minX, maxX, margin) — no map, no
 * server — so it is pinned directly. A real {@code MapleMap}/{@code Character} cannot be built in a
 * unit test (Spring static initializer), but {@link BotNavigationGraph.Region} is constructible from
 * plain {@link Foothold}s (the same way the graph builder makes them).
 */
class JumpLaunchWindowInsetTest {

    /** A ground region spanning [minX,maxX] at y (one horizontal foothold). */
    private static BotNavigationGraph.Region region(int minX, int maxX, int y) {
        Foothold fh = new Foothold(new Point(minX, y), new Point(maxX, y), 1);
        return new BotNavigationGraph.Region(1, List.of(new BotNavigationGraph.Segment(fh, false)));
    }

    @Test
    void insetsBothEndsByTheMarginWhenThereIsRoom() {
        BotNavigationGraph.Region r = region(-100, 100, 0);
        int[] w = BotNavigationGraphProvider.insetJumpLaunchWindow(r, 0, 80, 6);
        assertEquals(6, w[0], "min end pulled inward by the margin");
        assertEquals(74, w[1], "max end pulled inward by the margin");
    }

    @Test
    void aWindowNarrowerThanTwiceTheMarginCollapsesToItsCentreNotEmpty() {
        BotNavigationGraph.Region r = region(-100, 100, 0);
        // Window [40,44], margin 6 -> 46 > 38: too thin to inset, must not empty.
        int[] w = BotNavigationGraphProvider.insetJumpLaunchWindow(r, 40, 44, 6);
        assertTrue(w[0] <= w[1], "the window must never be emptied (a lost edge strands bots)");
        assertEquals(42, w[0], "collapses to the window centre");
        assertEquals(42, w[1]);
    }

    @Test
    void aThinWindowPressedAgainstTheEdgeIsPulledOffTheEdge() {
        BotNavigationGraph.Region r = region(-100, 220, 0);
        // The pathological case: a 2px window sitting on the ledge's last pixels.
        int[] w = BotNavigationGraphProvider.insetJumpLaunchWindow(r, 218, 220, 6);
        assertTrue(w[1] <= 220, "must stay on the ledge");
        assertTrue(w[1] < 220, "the collapsed point must be pulled inside the ledge edge");
        assertEquals(w[0], w[1], "a collapsed window is a single point");
    }

    @Test
    void theMarginIsOneWalkStep() {
        int margin = BotNavigationGraphProvider.jumpLaunchMargin(null, BotMovementProfile.base());
        int walkStep = BotPhysicsEngine.walkStep(null, BotMovementProfile.base());
        assertEquals(walkStep, margin, "the inset must cover the executor's +/-walkStep launch phase");
        assertTrue(margin >= 1, "never zero, so an edge-pressed window is always pulled inward");
    }
}
