package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static soloMapling.ArtificialPlayer.GCMoveSystem.BotNavigationGraph.EdgeType.*;

class BotNavigationSearchTest {
    private static final Point ANCHOR = new Point(-200, -30);

    @Test
    void aCheaperArrivalReplacesThePredecessorAndSkipsTheStaleQueueEntry() {
        var expensive = walk(1, 2, 80);
        var detour = walk(1, 3, 10);
        var cheaper = walk(3, 2, 15);
        var last = walk(2, 4, 100);
        var graph = graph(Map.of(1, List.of(expensive, detour), 2, List.of(last), 3, List.of(cheaper)));

        var outcome = search(graph, null, 1, ANCHOR, 4, ANCHOR);

        assertEquals(List.of(detour, cheaper, last), outcome.path());
        assertEquals(125, outcome.cost());
        assertEquals(4, outcome.expandedNodes());
    }

    @Test
    void anEqualCostArrivalKeepsTheFirstRoute() {
        var first = walk(1, 2, 10);
        var second = walk(1, 3, 10);
        var firstExit = walk(2, 4, 10);
        var secondExit = walk(3, 4, 10);
        var graph = graph(Map.of(1, List.of(first, second), 2, List.of(firstExit), 3, List.of(secondExit)));

        assertEquals(List.of(first, firstExit), search(graph, null, 1, ANCHOR, 4, ANCHOR).path());
    }

    @Test
    void aWalkAndAPortalToTheSameAnchorAreDistinctAndPortalsReadLiveStatus() {
        MapleMap map = mock(MapleMap.class);
        Portal entry = mock(Portal.class);
        Portal exit = mock(Portal.class);
        when(map.getPortal(1)).thenReturn(entry);
        when(map.getPortal(2)).thenReturn(exit);
        when(entry.getPortalStatus()).thenReturn(true);
        when(exit.getPortalStatus()).thenReturn(true);

        var firstPortal = new BotNavigationGraph.Edge(1, 2, PORTAL, ANCHOR, ANCHOR, 0, 1, 0, 0, 0, 0);
        var walkToExit = walk(1, 2, 60);
        var secondPortal = new BotNavigationGraph.Edge(2, 3, PORTAL, ANCHOR, ANCHOR, 0, 2, 0, 0, 0, 0);
        var walkOut = walk(2, 3, 1000);
        var graph = graph(Map.of(1, List.of(firstPortal, walkToExit), 2, List.of(secondPortal, walkOut)));

        // Re-entering directly from the first portal incurs a cooldown; walking there does not.
        var open = search(graph, map, 1, ANCHOR, 3, ANCHOR);
        assertEquals(List.of(walkToExit, secondPortal), open.path());
        assertEquals(60, open.cost());
        assertTrue(open.usesPortal());

        // The exit can open or close between two expansions of region 2 in the same search.
        when(exit.getPortalStatus()).thenReturn(false, true);
        assertEquals(List.of(walkToExit, secondPortal), search(graph, map, 1, ANCHOR, 3, ANCHOR).path());
        when(exit.getPortalStatus()).thenReturn(true, false);
        var closing = search(graph, map, 1, ANCHOR, 3, ANCHOR);
        assertEquals(List.of(firstPortal, secondPortal), closing.path());
        assertEquals(250, closing.cost());

        when(exit.getPortalStatus()).thenReturn(false);
        var closed = search(graph, map, 1, ANCHOR, 3, ANCHOR);
        assertEquals(List.of(firstPortal, walkOut), closed.path());
        assertEquals(1000, closed.cost());
    }

    @Test
    void aStraightDropLandsAtTheNearestWindowColumnNotTheAuthoredMidpoint() {
        var floor = new Foothold(new Point(-400, 100), new Point(-100, 100), 1);
        var destination = new BotNavigationGraph.Region(2, List.of(new BotNavigationGraph.Segment(floor)));
        var drop = new BotNavigationGraph.Edge(1, 2, DROP, new Point(-250, 0), new Point(-250, 100),
                -300, -200, 0, 0, 0, 0, 0, 25);
        var graph = graph(Map.of(1, List.of(drop)), destination);

        // No live map is needed for the landing verdict: an unindexed surface is livable.
        var outcome = search(graph, null, 1, new Point(-300, 0), 2, new Point(-300, 100));

        assertEquals(List.of(drop), outcome.path());
        assertEquals(25, outcome.cost());

        var outside = search(graph, null, 1, new Point(-175, 0), 2, new Point(-200, 100));
        int approachCost = (int) Math.round(25_000.0 / Math.max(1.0, graph.movementProfile.walkVelocityPxs()));
        assertEquals(List.of(drop), outside.path());
        assertEquals(25 + approachCost, outside.cost());
    }

    @Test
    void theSameDropEdgeCanLandAtDifferentColumnsInOneSearch() {
        var left = new Point(-300, 0);
        var right = new Point(-200, 0);
        var arrivalLeft = new BotNavigationGraph.Edge(1, 2, WALK, ANCHOR, left, 0, 0, 0, 0, 0, 5);
        var arrivalRight = new BotNavigationGraph.Edge(1, 2, WALK, ANCHOR, right, 0, 0, 0, 0, 0, 20);
        var drop = new BotNavigationGraph.Edge(2, 3, DROP, new Point(-250, 0), new Point(-250, 100),
                -300, -200, 0, 0, 0, 0, 0, 25);
        var floor = new Foothold(new Point(-400, 100), new Point(-100, 100), 1);
        var destination = new BotNavigationGraph.Region(3, List.of(new BotNavigationGraph.Segment(floor)));
        var graph = graph(Map.of(1, List.of(arrivalLeft, arrivalRight), 2, List.of(drop)), destination);

        var outcome = search(graph, null, 1, ANCHOR, 3, new Point(-200, 100));

        assertEquals(List.of(arrivalRight, drop), outcome.path());
        assertEquals(45, outcome.cost());
    }

    @Test
    void aBlockedBlinkDoesNotLeakToAnotherBotsSearch() {
        var blink = new BotNavigationGraph.Edge(1, 2, TELEPORT, ANCHOR, ANCHOR, 0, 0, 0, 0, 0, 5);
        var graph = graph(Map.of(1, List.of(blink)));

        assertNull(BotNavigationManager.runSearch(graph, null, ANCHOR, 1, 2, ANCHOR,
                null, true, false, 0L, false).path());
        assertEquals(List.of(blink), search(graph, null, 1, ANCHOR, 2, ANCHOR).path());
        assertNull(BotNavigationManager.runSearch(graph, null, ANCHOR, 1, 2, ANCHOR,
                null, true, false, 0L, false).path());
    }

    @Test
    void arrivalsAtDifferentHeightsOnTheSameRopeRemainDistinct() {
        var top = new Point(-200, 0);
        var rope = new BotNavigationGraph.Region(2, top.x, top.y, 200, false);
        var low = new BotNavigationGraph.Edge(1, 2, CLIMB, ANCHOR, new Point(top.x, 200), 0, 0, 0, 0, 0, 5);
        var high = new BotNavigationGraph.Edge(1, 2, CLIMB, ANCHOR, top, 0, 0, 0, 0, 0, 40);
        var exit = new BotNavigationGraph.Edge(2, 3, CLIMB, top, top, 0, 0, 0, 0, 0, 10);
        var graph = graph(Map.of(1, List.of(low, high), 2, List.of(exit)), rope);

        var outcome = search(graph, null, 1, ANCHOR, 3, top);

        assertEquals(List.of(high, exit), outcome.path());
        assertEquals(50, outcome.cost());
    }

    @Test
    void growingTheStateTablePreservesTheWholePathAndPreviouslyVisitedStates() {
        Map<Integer, List<BotNavigationGraph.Edge>> outgoing = new HashMap<>();
        List<BotNavigationGraph.Edge> expected = new ArrayList<>();
        for (int id = 1000; id < 1100; id++) {
            var next = walk(id, id + 1, 1);
            outgoing.put(id, List.of(walk(id, 1000, 0), next));
            expected.add(next);
        }

        var outcome = search(graph(outgoing), null, 1000, ANCHOR, 1100, ANCHOR);

        assertEquals(expected, outcome.path());
        assertEquals(100, outcome.cost());
        assertEquals(101, outcome.expandedNodes());
    }

    @Test
    void unreachableAndNoEdgesNeededStayDistinct() {
        var graph = graph(Map.of());

        assertNull(search(graph, null, 1, ANCHOR, 2, ANCHOR).path());
        assertEquals(List.of(), search(graph, null, 1, ANCHOR, 1, ANCHOR).path());
    }

    private static BotNavigationManager.SearchOutcome search(BotNavigationGraph graph, MapleMap map, int from,
                                                              Point start, int to, Point target) {
        return BotNavigationManager.runSearch(graph, map, start, from, to, target, null, true, false, 0L);
    }

    private static BotNavigationGraph.Edge walk(int from, int to, int cost) {
        return new BotNavigationGraph.Edge(from, to, WALK, ANCHOR, ANCHOR, 0, 0, 0, 0, 0, cost);
    }

    private static BotNavigationGraph graph(Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                             BotNavigationGraph.Region... regions) {
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        for (var region : regions) byId.put(region.id, region);
        return new BotNavigationGraph(1, 1, List.of(regions), byId, Map.of(), outgoing, Set.of());
    }
}
