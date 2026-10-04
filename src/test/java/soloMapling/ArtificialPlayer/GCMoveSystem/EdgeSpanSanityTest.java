package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import java.awt.Point;
import static org.junit.jupiter.api.Assertions.*;

/** Sanity pins for the NO_VALID_SPAN rework in BotNavigationGraph.Edge. */
class EdgeSpanSanityTest {

    private static BotNavigationGraph.Edge edge(int launchMinX, int launchMaxX,
                                                int validMinX, int validMaxX) {
        return new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(launchMinX, 0), new Point(launchMinX, 0),
                launchMinX, launchMaxX, validMinX, validMaxX, 8, 0, 0, 0, 0, 100);
    }

    @Test
    void aRealSpanAtZeroIsHonouredNotTreatedAsLegacy() {
        BotNavigationGraph.Edge e = edge(0, 0, 0, 0);
        // A window whose stamped pixel AND validated span are both exactly 0: after the
        // constructor's legacy normalization a [0,0] input reads as NO_VALID_SPAN, so the
        // executor falls back to stamped+tolerance (-8..8). This is the residual ambiguity:
        // pinned here so the trade-off is visible (a real x=0 span is not clamped).
        assertTrue(e.acceptsLaunchX(0, 8));
        assertTrue(e.acceptsLaunchX(8, 8));
        assertFalse(e.acceptsLaunchX(9, 8));
    }

    @Test
    void aLegacyZeroZeroInputNormalizesToTheSentinel() {
        BotNavigationGraph.Edge e = edge(100, 140, 0, 0); // legacy "not recorded"
        assertEquals(BotNavigationGraph.Edge.NO_VALID_SPAN, e.launchValidMinX);
        assertEquals(BotNavigationGraph.Edge.NO_VALID_SPAN, e.launchValidMaxX);
        // Falls back to the STAMPED window (no tolerance extension past 100..140 for x=150 —
        // it's outside the window entirely), but the stamped window itself is honoured.
        assertTrue(e.acceptsLaunchX(140, 8));
        assertFalse(e.acceptsLaunchX(150, 8), "outside the stamped window [100,140]");
    }

    @Test
    void aWideValidSpanClampsInsideTheWindow() {
        BotNavigationGraph.Edge e = edge(100, 140, 104, 136);
        assertTrue(e.acceptsLaunchX(104, 8));
        assertFalse(e.acceptsLaunchX(100, 8), "stamped edge pixels outside the validated span are refused");
        assertFalse(e.acceptsLaunchX(140, 8));
    }
}
