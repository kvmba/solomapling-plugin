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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
    void aPendingTieKeepsTheJdkHeapOrderEvenAfterGrowth() throws Exception {
        List<BotNavigationGraph.Edge> branches = new ArrayList<>();
        Map<Integer, List<BotNavigationGraph.Edge>> outgoing = new HashMap<>();
        for (int region = 10; region < 74; region++) {
            branches.add(walk(1, region, 10));
            if (region != 10) outgoing.put(region, List.of(walk(region, 2, 0)));
        }
        outgoing.put(1, branches);
        var graph = graph(outgoing);

        // The first leaf is a dead end. The old heap moves its last entry to the root on removal
        // and leaves it there when both children tie, so the last branch wins, not the second.
        onSearchWorker(() -> {
            var outcome = search(graph, null, 1, ANCHOR, 2, ANCHOR);
            var direct = walk(1, 2, 90);
            var next = search(graph(Map.of(1, List.of(direct))), null, 1, ANCHOR, 2, ANCHOR);
            assertEquals(List.of(direct), next.path());
            assertEquals(90, next.cost());
            assertEquals(List.of(branches.getLast(), outgoing.get(73).getFirst()), outcome.path());
            assertEquals(10, outcome.cost());
        });
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
    void aReentrantSearchCannotOverwritePendingStates() throws Exception {
        var sameKey = walk(1, 2, 999);
        var nestedGraph = graph(Map.of(1, List.of(sameKey)));
        var firstLeg = walk(1, 3, 10);
        var pending = walk(1, 2, 80);
        var exit = new BotNavigationGraph.Edge(3, 2, PORTAL, ANCHOR, ANCHOR, 0, 1, 0, 0, 0, 0);
        var outerGraph = graph(Map.of(1, List.of(firstLeg, pending), 3, List.of(exit)));
        MapleMap map = mock(MapleMap.class);
        Portal portal = mock(Portal.class);
        when(map.getPortal(1)).thenReturn(portal);
        when(portal.getPortalStatus()).thenAnswer(ignored -> {
            assertEquals(List.of(sameKey), search(nestedGraph, null, 1, ANCHOR, 2, ANCHOR).path());
            return true;
        });

        onSearchWorker(() -> {
            var outcome = search(outerGraph, map, 1, ANCHOR, 2, ANCHOR);
            assertEquals(List.of(firstLeg, exit), outcome.path());
            assertEquals(10, outcome.cost());
        });
    }

    @Test
    void aFailedSearchLeavesNoStateForTheNextSearchOnTheSameWorker() throws Exception {
        var walk = walk(1, 2, 80);
        var exit = new BotNavigationGraph.Edge(1, 2, PORTAL, ANCHOR, ANCHOR, 0, 1, 0, 0, 0, 0);
        var graph = graph(Map.of(1, List.of(walk, exit)));
        MapleMap map = mock(MapleMap.class);
        Portal portal = mock(Portal.class);
        when(map.getPortal(1)).thenReturn(portal);
        when(portal.getPortalStatus()).thenThrow(new IllegalStateException("portal read failed")).thenReturn(false);

        onSearchWorker(() -> {
            assertThrows(IllegalStateException.class, () -> search(graph, map, 1, ANCHOR, 2, ANCHOR));
            var outcome = search(graph, map, 1, ANCHOR, 2, ANCHOR);
            assertEquals(List.of(walk), outcome.path());
            assertEquals(80, outcome.cost());
        });
    }

    @Test
    void walkingCostsDoNotLeakBetweenMovementProfilesAndMatchAVirtualThread() throws Exception {
        var leg = walk(1, 2, 8);
        var outgoing = Map.of(1, List.of(leg));
        var normal = graph(outgoing);
        var fast = new BotNavigationGraph(1, 1, new BotMovementProfile(125, 100),
                List.of(), Map.of(), Map.of(), outgoing, Set.of());
        var start = new Point(ANCHOR.x - 101, ANCHOR.y);
        var farTarget = new Point(ANCHOR.x + 2001, ANCHOR.y);
        // Initialize the host movement constants before exercising the virtual-thread search itself.
        double speed = Math.max(1.0, fast.movementProfile.walkVelocityPxs());
        long expectedCost = leg.cost + Math.round(101_000.0 / speed) + Math.round(2_001_000.0 / speed);

        var fallback = new FutureTask<>(() -> {
            assertTrue(Thread.currentThread().isVirtual());
            return search(fast, null, 1, start, 2, farTarget);
        });
        Thread.startVirtualThread(fallback);
        var expectedFast = fallback.get(10, TimeUnit.SECONDS);

        onSearchWorker(() -> {
            var first = search(normal, null, 1, start, 2, farTarget);
            var next = search(fast, null, 1, start, 2, farTarget);
            var again = search(normal, null, 1, start, 2, farTarget);
            assertEquals(List.of(leg), first.path());
            assertEquals(expectedFast, next);
            assertEquals(expectedCost, next.cost());
            assertTrue(next.cost() < first.cost());
            assertEquals(first, again);
        });
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

    private static void onSearchWorker(Runnable check) throws Exception {
        var task = new FutureTask<Void>(check, null);
        var thread = new BotNavigationManager.SearchWorker(task, "test-movement-search");
        thread.setDaemon(true);
        thread.start();
        task.get(10, TimeUnit.SECONDS);
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
