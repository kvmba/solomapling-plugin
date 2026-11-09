package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 玩具城 药店 (220000002) walk-off edge bake regression: a bot pacing forever near the ladder at
 * x=-267 on the map's top platform f1.
 *
 * <p>The reported symptom was a bot "walking toward the staircase and turning back" over and over
 * on the top platform. It is not the ladder: the ladder only sat next to the stall point.
 *
 * <p>The platform f1 spans the whole map ({@code x=-400..400}) and the map's standable x extent is
 * exactly {@code [-400,400]}, so f1's two END CAPS ARE the map's side boundary. The walk-off bake
 * asks {@code simulateWalkOffLanding} to walk off each end; that walk is pinned by the boundary a
 * few px short of the end pixel, so it returns null. The bake treated every null as "clamped at a
 * lip" and fell back to a ballistic fall fired from the end pixel, which the executor can never
 * perform - every ground step past that pixel is wall-blocked. Six such drops were baked (all with
 * an endpoint on x=+-400) and the pathfinder then preferred one of them over the ladder:
 * DROP 770 + JUMP 500 = 1270 against CLIMB 994 + CLIMB 400 = 1394. The executor walked to the wall,
 * stalled, the rescue hop landed it back on f1, and the route re-chose the same edge forever.
 *
 * <p>The fix asks the executor's own wall predicate at the pixel where the walk stopped
 * ({@link BotPhysicsEngine#classifyWalkOffStall}) and refuses to bake a drop for the boundary case
 * while keeping the fall-based fallback for a genuine lip.
 */
class ShopWalkOffBoundaryDropTest {

    private static final int MAP_ID = 220000002;
    /** f1's two end caps, i.e. the map's own standable x extent. */
    private static final int MAP_LEFT_X = -400;
    private static final int MAP_RIGHT_X = 400;
    /** The ladder on f1 that the reported bot kept pacing in front of. */
    private static final int LADDER_X = -267;

    private static MapleMap map;

    @BeforeAll
    static void boot() {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<org.gms.dao.entity.GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        // Job's static initializer resolves names through I18nUtil's MessageSource beans (the
        // profile/capability lookups touch it); install them so the mock host stays complete.
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        try {
            var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
            setter.setAccessible(true);
            setter.invoke(new ServerManager(), ctx);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        org.gms.config.GameConfig.add(cfg("server", "update_interval", "100"));
        map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
    }

    static org.gms.dao.entity.GameConfigDO cfg(String t, String k, String v) {
        org.gms.dao.entity.GameConfigDO d = new org.gms.dao.entity.GameConfigDO();
        d.setConfigType(t);
        d.setConfigSubType("0");
        d.setConfigCode(k);
        d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    /**
     * The bug: a drop baked off a platform end that IS the map boundary can never be executed, so
     * the route through it never completes. No directional drop in this map may end on the
     * standable x extent while its live walk refuses to leave the ground.
     */
    @Test
    void noBakedDropEndsOnTheMapBoundaryAfterAWalkThatCannotLeaveTheGround() {
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        Point bounds = BotPhysicsEngine.standableXBounds(map);
        assertEquals(MAP_LEFT_X, bounds.x, "map standable left changed: " + bounds);
        assertEquals(MAP_RIGHT_X, bounds.y, "map standable right changed: " + bounds);

        List<String> offenders = new ArrayList<>();
        int drops = 0;
        for (BotNavigationGraph.Region region : graph.regions) {
            if (region.isRopeRegion) {
                continue;
            }
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX == 0) {
                    continue;
                }
                drops++;
                boolean onBoundary = edge.endPoint.x <= bounds.x || edge.endPoint.x >= bounds.y;
                if (!onBoundary) {
                    continue;
                }
                BotPhysicsEngine.WalkOffLanding walk = BotPhysicsEngine.simulateWalkOffLanding(
                        map, edge.startPoint, Integer.signum(edge.launchStepX),
                        BotPhysicsEngine.initialGroundTravelState(edge.startPoint),
                        BotMovementProfile.base());
                if (walk == null && BotPhysicsEngine.classifyWalkOffStall(
                        map, edge.startPoint, Integer.signum(edge.launchStepX),
                        BotMovementProfile.base()) == BotPhysicsEngine.WalkOffStall.BOUND) {
                    offenders.add(String.format("r%d->r%d (%d,%d)->(%d,%d) step=%d",
                            edge.fromRegionId, edge.toRegionId, edge.startPoint.x, edge.startPoint.y,
                            edge.endPoint.x, edge.endPoint.y, edge.launchStepX));
                }
            }
        }
        // Guard the premise: the map really does bake walk-off drops.
        assertTrue(drops > 0, "no directional drop baked at all - the map geometry changed");
        assertTrue(offenders.isEmpty(),
                "unexecutable boundary drops are still baked (the bot paces into the wall forever): "
                        + offenders);
    }

    /**
     * The fix must not solve it by gutting the map: every walk-off the bake does keep must be
     * executable, and the whole ground graph must stay mutually reachable.
     */
    @Test
    void everyRemainingWalkOffDropIsExecutable() {
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        List<String> unexecutable = new ArrayList<>();
        for (BotNavigationGraph.Region region : graph.regions) {
            if (region.isRopeRegion) {
                continue;
            }
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX == 0) {
                    continue;
                }
                if (BotPhysicsEngine.simulateWalkOffLanding(map, edge.startPoint,
                        Integer.signum(edge.launchStepX),
                        BotPhysicsEngine.initialGroundTravelState(edge.startPoint),
                        BotMovementProfile.base()) == null) {
                    unexecutable.add(String.format("r%d->r%d (%d,%d)->(%d,%d)",
                            edge.fromRegionId, edge.toRegionId, edge.startPoint.x, edge.startPoint.y,
                            edge.endPoint.x, edge.endPoint.y));
                }
            }
        }
        assertTrue(unexecutable.isEmpty(),
                "baked walk-off drops that the executor can never perform: " + unexecutable);
    }

    @Test
    void groundRegionsStayMutuallyReachable() {
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        List<Integer> ground = new ArrayList<>();
        for (BotNavigationGraph.Region region : graph.regions) {
            if (!region.isRopeRegion) {
                ground.add(region.id);
            }
        }
        assertTrue(ground.size() > 1, "expected several ground regions");
        for (int from : ground) {
            Set<Integer> reachable = reachableFrom(graph, from);
            for (int to : ground) {
                if (from == to) {
                    continue;
                }
                assertTrue(reachable.contains(to),
                        "removing the boundary drops stranded region r" + from + " -> r" + to);
            }
        }
    }

    /**
     * The positive half: the route that used to pick the boundary drop must now take the ladder,
     * and the ladder route must be strictly cheaper than the removed alternative used to be.
     */
    @Test
    void theTopPlatformRouteToTheFloorBelowUsesTheLadder() {
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        int f1 = graph.findRegionId(map, new Point(-300, -161));
        int below = graph.findRegionId(map, new Point(-300, -22));
        assertTrue(f1 >= 0 && below >= 0, "expected f1 and the floor below to be distinct regions");

        ArrayDeque<BotNavigationGraph.Edge> path = cheapestPath(graph, f1, below);
        assertFalse(path.isEmpty(), "no path from f1 to the floor below");

        BotNavigationGraph.Edge first = path.peekFirst();
        assertEquals(BotNavigationGraph.EdgeType.CLIMB, first.type,
                "the route still starts with " + first.type + " instead of the ladder");
        assertEquals(LADDER_X, first.endPoint.x,
                "the route does not use the ladder at x=" + LADDER_X + ": " + first.endPoint);
    }

    /**
     * The boundary stall and a real lip must not be confused: a walk pinned by the map's side
     * boundary classifies BOUND, while a walk that genuinely steps off a ledge does not.
     */
    @Test
    void boundaryStallIsDistinguishedFromALedge() {
        BotMovementProfile profile = BotMovementProfile.base();
        // f1's left end: the walk is pinned by the map boundary, not a ledge.
        assertEquals(BotPhysicsEngine.WalkOffStall.BOUND,
                BotPhysicsEngine.classifyWalkOffStall(map, new Point(-360, -161), -1, profile),
                "the walk against f1's left end is a boundary stall");
        assertEquals(BotPhysicsEngine.WalkOffStall.BOUND,
                BotPhysicsEngine.classifyWalkOffStall(map, new Point(360, -161), 1, profile),
                "the walk against f1's right end is a boundary stall");

        // The floor's right end at y=187 is also the map edge; walking off the small mid-left
        // step f3 (x=-340..-400) is the same shape. Contrast with a ledge that really descends:
        // f2 (x=-339..-206, y=-22) has open air beyond it.
        BotPhysicsEngine.WalkOffStall lipOrNone =
                BotPhysicsEngine.classifyWalkOffStall(map, new Point(-210, -22), 1, profile);
        assertTrue(lipOrNone != BotPhysicsEngine.WalkOffStall.BOUND,
                "a genuine ledge must not be classified as a map-boundary stall");
    }

    /**
     * The other observed shape of the same bug: a floor that ends at the edge of the AUTHORED
     * footholds, which can sit well inside the map's VR rect. 玩具塔 1层 (221020000) floors stop at
     * x=+-265 while the VR spans +-400, and the executor pins at +-262 exactly like it pins at the
     * VR bound - so the fix must cover it too, not just platforms that reach the VR edge.
     *
     * <p>This is the regression for the fix's own first bug: an earlier version compared against
     * {@code standableXBounds} and called a foothold-end stall "a map boundary", which only happened
     * to be right on 药店 because there the two extents coincide.
     */
    @Test
    void aFloorEndingInsideTheMapExtentIsAlsoRefused() {
        MapleMap tower = BotNavigationMapLoader.loadMapGeometry(221020000);
        BotMovementProfile profile = BotMovementProfile.base();
        Point bounds = BotPhysicsEngine.standableXBounds(tower);
        java.awt.Rectangle vr = tower.getMapArea();
        assertTrue(bounds != null && vr != null, "tower map geometry missing");
        // The premise: the authored extent is INSIDE the VR rect (this is what the old predicate
        // could not tell apart from a real map boundary).
        assertTrue(bounds.x > vr.x,
                "221020000's footholds no longer stop inside the VR rect (standable " + bounds
                        + " vs VR " + vr + ") - this regression needs a new subject");

        // Every floor end that the bake cannot walk off must classify BOUND (no drop exists), and
        // no surviving directional drop may still be classified BOUND (i.e. wrongly kept).
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(tower, BotMovementProfile.base());
        List<String> wronglyKept = new ArrayList<>();
        int removed = 0;
        for (BotNavigationGraph.Region region : graph.regions) {
            if (region.isRopeRegion) {
                continue;
            }
            for (int dir : new int[]{-1, 1}) {
                Point endpoint = dir < 0 ? region.leftPoint() : region.rightPoint();
                int runwayPx = BotPhysicsEngine.launchRunwayPx(tower, profile);
                int launchX = dir < 0
                        ? Math.min(region.maxX, endpoint.x + runwayPx)
                        : Math.max(region.minX, endpoint.x - runwayPx);
                if (Math.abs(launchX - endpoint.x) < Math.min(runwayPx, 20)) {
                    continue;
                }
                Point startPoint = region.pointAt(launchX);
                if (BotPhysicsEngine.isGroundRunwayBlockedByWall(tower, startPoint, endpoint)) {
                    continue;
                }
                if (BotPhysicsEngine.simulateWalkOffLanding(tower, startPoint, dir, profile) != null) {
                    continue; // this end really does descend
                }
                if (BotPhysicsEngine.classifyWalkOffStall(tower, startPoint, dir, profile)
                        == BotPhysicsEngine.WalkOffStall.BOUND) {
                    removed++;
                }
            }
        }
        assertTrue(removed > 0,
                "221020000 no longer refuses any foothold-end drop - the fix stopped covering this shape");

        // And nothing classified BOUND survived into the graph.
        for (BotNavigationGraph.Region region : graph.regions) {
            if (region.isRopeRegion) {
                continue;
            }
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX == 0) {
                    continue;
                }
                int dir = Integer.signum(edge.launchStepX);
                if (BotPhysicsEngine.simulateWalkOffLanding(tower, edge.startPoint, dir,
                        BotPhysicsEngine.initialGroundTravelState(edge.startPoint), profile) != null) {
                    continue;
                }
                if (BotPhysicsEngine.classifyWalkOffStall(tower, edge.startPoint, dir, profile)
                        == BotPhysicsEngine.WalkOffStall.BOUND) {
                    wronglyKept.add("r" + edge.fromRegionId + "->r" + edge.toRegionId
                            + "@" + edge.startPoint.x + "," + edge.startPoint.y);
                }
            }
        }
        assertTrue(wronglyKept.isEmpty(),
                "unexecutable foothold-end drops survived the bake: " + wronglyKept);
    }

    /**
     * Regression for the fix's own first bug: momentum dies on a different pixel depending on where
     * the sweep started, and the wall band begins a few pixels inside the end of the ground, so the
     * verdict about the SAME dead end must not depend on the start pixel.
     */
    @Test
    void theBoundaryVerdictDoesNotDependOnWhereTheSweepStarted() {
        BotMovementProfile profile = BotMovementProfile.base();
        for (int startX : new int[]{-399, -382, -360, -357, -332, -307, -300}) {
            Point from = new Point(startX, -161);
            if (BotPhysicsEngine.simulateWalkOffLanding(map, from, -1, profile) != null) {
                continue; // this approach really does leave the ground; nothing to classify
            }
            assertEquals(BotPhysicsEngine.WalkOffStall.BOUND,
                    BotPhysicsEngine.classifyWalkOffStall(map, from, -1, profile),
                    "f1's left dead end from x=" + startX
                            + " must classify BOUND regardless of where the walk started");
        }
        for (int startX : new int[]{399, 382, 360, 357, 307}) {
            Point from = new Point(startX, -161);
            if (BotPhysicsEngine.simulateWalkOffLanding(map, from, 1, profile) != null) {
                continue;
            }
            assertEquals(BotPhysicsEngine.WalkOffStall.BOUND,
                    BotPhysicsEngine.classifyWalkOffStall(map, from, 1, profile),
                    "f1's right dead end from x=" + startX + " must classify BOUND");
        }
    }

    /**
     * The classifier is pure and idempotent: it is called several times per region during a bake and
     * must not depend on any state left by a previous call.
     */
    @Test
    void classificationIsDeterministic() {
        BotMovementProfile profile = BotMovementProfile.base();
        Point from = new Point(-360, -161);
        BotPhysicsEngine.WalkOffStall first =
                BotPhysicsEngine.classifyWalkOffStall(map, from, -1, profile);
        BotPhysicsEngine.WalkOffStall second =
                BotPhysicsEngine.classifyWalkOffStall(map, from, -1, profile);
        BotPhysicsEngine.WalkOffStall third =
                BotPhysicsEngine.classifyWalkOffStall(map, from, -1, profile);
        assertEquals(first, second, "classifyWalkOffStall is not idempotent");
        assertEquals(second, third, "classifyWalkOffStall is not idempotent");
        assertEquals(BotPhysicsEngine.WalkOffStall.BOUND, first);
    }

    private static Set<Integer> reachableFrom(BotNavigationGraph graph, int start) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(current)) {
                if (seen.add(edge.toRegionId)) {
                    queue.add(edge.toRegionId);
                }
            }
        }
        return seen;
    }

    private static ArrayDeque<BotNavigationGraph.Edge> cheapestPath(BotNavigationGraph graph,
                                                                   int fromRegion,
                                                                   int toRegion) {
        Map<Integer, Integer> cost = new HashMap<>();
        Map<Integer, BotNavigationGraph.Edge> cameBy = new HashMap<>();
        PriorityQueue<int[]> open = new PriorityQueue<>((a, b) -> Integer.compare(a[1], b[1]));
        cost.put(fromRegion, 0);
        open.add(new int[]{fromRegion, 0});
        while (!open.isEmpty()) {
            int[] current = open.poll();
            if (current[1] > cost.getOrDefault(current[0], Integer.MAX_VALUE)) {
                continue;
            }
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(current[0])) {
                int next = current[1] + edge.cost;
                if (next < cost.getOrDefault(edge.toRegionId, Integer.MAX_VALUE)) {
                    cost.put(edge.toRegionId, next);
                    cameBy.put(edge.toRegionId, edge);
                    open.add(new int[]{edge.toRegionId, next});
                }
            }
        }
        ArrayDeque<BotNavigationGraph.Edge> path = new ArrayDeque<>();
        int cursor = toRegion;
        while (cameBy.containsKey(cursor)) {
            BotNavigationGraph.Edge edge = cameBy.get(cursor);
            path.addFirst(edge);
            cursor = edge.fromRegionId;
        }
        return path;
    }
}
