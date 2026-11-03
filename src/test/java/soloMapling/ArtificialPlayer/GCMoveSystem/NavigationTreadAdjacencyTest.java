package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level edge cases for the new Region helpers. These are the shapes that could throw or
 * mis-answer on real data, checked without a map.
 */
class NavigationTreadAdjacencyTest {

    private static BotNavigationGraph.Segment seg(int fh, int x1, int y1, int x2, int y2) {
        org.gms.server.maps.Foothold f = new org.gms.server.maps.Foothold(
                new Point(x1, y1), new Point(x2, y2), fh);
        return new BotNavigationGraph.Segment(f, false);
    }

    private static BotNavigationGraph.Region region(BotNavigationGraph.Segment... segs) {
        return new BotNavigationGraph.Region(1, List.of(segs));
    }

    @Test
    void segmentAtRejectsAWrongRow() {
        BotNavigationGraph.Region r = region(
                seg(1, -100, 0, 100, 0),
                seg(2, -100, 20, 100, 20));
        assertNotNull(r.segmentAt(0, 0), "the y=0 tread must be found");
        assertNotNull(r.segmentAt(0, 20), "the y=20 tread must be found");
        assertEquals(1, r.segmentAt(0, 0).footholdId);
        assertEquals(2, r.segmentAt(0, 20).footholdId);
        assertNull(r.segmentAt(0, 10), "a gap between rows must answer null, not the nearest row");
    }

    @Test
    void adjacentNeedsMeetingEndpointsNotJustOverlap() {
        // Overlapping x, 13px apart: the exact fh227/fh233 shape that must NOT be adjacent.
        BotNavigationGraph.Segment upper = seg(1, 26, 118, 49, 118);
        BotNavigationGraph.Segment lower = seg(2, 41, 131, 106, 131);
        assertFalse(BotNavigationGraph.Region.adjacent(upper, lower),
                "overlap alone must not read as a walkable join");

        // Joined at a shared endpoint: the ramp case.
        BotNavigationGraph.Segment ramp = seg(3, 26, 118, 41, 131);
        assertTrue(BotNavigationGraph.Region.adjacent(lower, ramp), "shared endpoint (41,131)");
        assertTrue(BotNavigationGraph.Region.adjacent(upper, ramp), "shared endpoint (26,118)");
    }

    @Test
    void nextTreadTowardReturnsTheFirstStepOffTheFeet() {
        BotNavigationGraph.Segment lower = seg(2, 41, 131, 106, 131);
        BotNavigationGraph.Segment ramp = seg(3, 26, 118, 41, 131);
        BotNavigationGraph.Segment upper = seg(1, 26, 118, 49, 118);
        BotNavigationGraph.Segment left = seg(4, -181, 118, 26, 118);
        BotNavigationGraph.Region r = region(lower, ramp, upper, left);

        BotNavigationGraph.Segment first = r.nextTreadToward(lower, upper);
        assertNotNull(first);
        assertEquals(3, first.footholdId, "the ramp is the first step off the lower tread");
        assertNull(r.nextTreadToward(lower, lower), "same tread has no next step");
    }

    @Test
    void sharedColumnIsTheRealJoin() {
        BotNavigationGraph.Segment ramp = seg(3, 26, 118, 41, 131);
        BotNavigationGraph.Segment lower = seg(2, 41, 131, 106, 131);
        assertEquals(41, BotNavigationGraph.Region.sharedColumnX(ramp, lower),
                "the join is the shared endpoint, not the overlap midpoint");
    }

    @Test
    void disconnectedTreadsAnswerNullAndDoNotLoop() {
        BotNavigationGraph.Segment a = seg(1, 0, 0, 10, 0);
        BotNavigationGraph.Segment b = seg(2, 500, 0, 510, 0);
        BotNavigationGraph.Region r = region(a, b);
        assertNull(r.nextTreadToward(a, b), "no chain between them");
    }
}
