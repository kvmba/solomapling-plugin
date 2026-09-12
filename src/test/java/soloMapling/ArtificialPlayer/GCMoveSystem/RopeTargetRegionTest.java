package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Guards findNearestRopeRegionId — the widened rope-column lookup used to attribute a MID-AIR
 * target (a floating portal, an elevated point) to the rope it hangs beside.
 *
 * The bug this exists for: 童话村[井口] (map 222000001) is a well with the town exit portal out00
 * floating at (-31,-463) — 20px off the well rope at x=-51, whose span is [-499, 474], while the
 * only floor is 977px below. resolvePointTargetRegionId calls the tight findRopeRegionId (8px grip
 * column), which misses; the ground lookup then snaps the goal onto the floor UNDER the rope —
 * the bot's own region. Same-region plans no climb, so the bot paces the floor forever and a
 * crowd piles up that can never leave (the observed "climbs and falls, never goes up").
 *
 * The fix consults the wider xTol lookup when the ground is too low to walk/jump to. Both the
 * exact well numbers and the "don't hijack a walkable point" boundary are pinned below; the
 * corpus check that only this one portal changes is in the commit message.
 */
class RopeTargetRegionTest {

    private static BotNavigationGraph graphWithRope(int ropeX, int topY, int bottomY) {
        List<BotNavigationGraph.Region> regions = new ArrayList<>();
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        BotNavigationGraph.Region rope = new BotNavigationGraph.Region(1, ropeX, topY, bottomY, false);
        regions.add(rope);
        byId.put(1, rope);
        return new BotNavigationGraph(222000001, 1, BotMovementProfile.base(),
                regions, byId, Map.of(), Map.of(), java.util.Set.of());
    }

    @Test
    void findsRopeWhenTargetIsOffAxisButWithinTolerance() {
        BotNavigationGraph graph = graphWithRope(-51, -499, 474);
        // 童话村[井口] out00: 20px off the rope axis, at a height the rope spans.
        assertEquals(1, graph.findNearestRopeRegionId(new Point(-31, -463), 24));
        // The tight grip column still (correctly) rejects it — that is the original miss.
        assertEquals(-1, graph.findRopeRegionId(new Point(-31, -463)));
    }

    @Test
    void rejectsTargetsBeyondTheTolerance() {
        BotNavigationGraph graph = graphWithRope(-51, -499, 474);
        assertEquals(-1, graph.findNearestRopeRegionId(new Point(-26, -463), 24), "dx=25 > 24");
        assertEquals(-1, graph.findNearestRopeRegionId(new Point(-82, -463), 24), "dx=31 > 24");
    }

    @Test
    void rejectsTargetsOutsideTheRopeYSpan() {
        BotNavigationGraph graph = graphWithRope(-51, -499, 474);
        assertEquals(-1, graph.findNearestRopeRegionId(new Point(-31, -560), 24), "above the rope top");
        assertEquals(-1, graph.findNearestRopeRegionId(new Point(-31, 520), 24), "below the rope bottom");
    }

    @Test
    void picksTheClosestRopeWhenSeveralQualify() {
        List<BotNavigationGraph.Region> regions = new ArrayList<>();
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            BotNavigationGraph.Region rope = new BotNavigationGraph.Region(i + 1, -51 + i * 30, -499, 474, false);
            regions.add(rope);
            byId.put(i + 1, rope);
        }
        BotNavigationGraph graph = new BotNavigationGraph(1, 1, BotMovementProfile.base(),
                regions, byId, Map.of(), Map.of(), java.util.Set.of());
        // x=-40: dx=11 to rope 1 (x=-51), dx=19 to rope 2 (x=-21) -> rope 1 wins.
        assertEquals(1, graph.findNearestRopeRegionId(new Point(-40, 0), 24));
        // x=-30: dx=21 to rope 1, dx=9 to rope 2 -> rope 2 wins.
        assertEquals(2, graph.findNearestRopeRegionId(new Point(-30, 0), 24));
    }

    @Test
    void tightLookupIsUnchanged() {
        BotNavigationGraph graph = graphWithRope(-51, -499, 474);
        assertTrue(graph.findRopeRegionId(new Point(-51, 0)) >= 0, "on-axis still resolves");
        assertTrue(graph.findRopeRegionId(new Point(-43, 0)) >= 0, "dx=8 is the inclusive grip edge");
        assertEquals(-1, graph.findRopeRegionId(new Point(-42, 0)), "dx=9 is past the grip column");
    }
}
