package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the LAUNCH-SITE half of the committed-flight contract (8a3df32): the executor must RECORD
 * the edge it launched for ({@code BotMovementState.navFlightEdge}) at every rope-exit branch that
 * leaves the bot committed to an authored arc, because {@code reuseCommittedEdge}'s mid-air
 * retention clause reads that single fact to keep the arc committed (a mid-air replan must not
 * steer the bot off the trajectory the graph validated).
 *
 * <p>Why this test exists: the shipped regression pair pinned the READER (a state with the fact set
 * is retained) and one end-to-end fire path, but mutating out any launch-site write left the whole
 * suite green - the writer half of the contract was unpinned. Four branches write the fact:
 *
 * <ol>
 *   <li>the BALLISTIC fire (jumpOffRope + fixedAirArc);</li>
 *   <li>the physics-owned TOP STEP-OFF (physics lands the top boundary);</li>
 *   <li>the physics-owned CLIMB-DOWN delegation (endPoint at/below startPoint);</li>
 *   <li>the upward exit whose physics top landing already resolves into the edge's target region
 *       (no jump is fired at all).</li>
 * </ol>
 *
 * <p>Each case below drives the PRODUCTION executor entry ({@code resolveTarget}) with a climbing
 * state on the REAL 922010100 tower geometry, at the edge's own authored anchor, so the executor's
 * real branch selection decides whether the fact is recorded. Deleting any of the four writes turns
 * the matching case red.
 */
public class RopeExitLaunchSiteRecordTest {

    private static final int MAP_ID = 922010100;

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        assumeTrue(mapWzAvailable(), "Map.wz needed");
    }

    static boolean mapWzAvailable() {
        try {
            var s = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return s.getData("Map/Map9/922010100.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    /** A climbing state on {@code rope} at the edge's own anchor, holding the committed edge. */
    private static BotMovementState climbingStateAtAnchor(MapleMap map, Rope rope,
                                                          BotNavigationGraph.Edge edge) {
        return climbingStateAtAnchor(map, rope, new Point(rope.x(), edge.startPoint.y));
    }

    private static BotMovementState climbingStateAtAnchor(MapleMap map, Rope rope, Point anchor) {
        return climbingStateAtAnchor(map, rope, BotMovementProfile.base(), anchor);
    }

    private static BotMovementState climbingStateAtAnchor(MapleMap map, Rope rope,
                                                          BotMovementProfile profile, Point anchor) {
        AtomicReference<Point> pos = new AtomicReference<>(anchor);
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP_ID);
        when(bot.getId()).thenReturn(9201);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        org.mockito.Mockito.doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        // Freeze the 20s profile recompute: the mock's stat accessors return deep-stub garbage,
        // which would wreck the profile (and the graph-key identity) on the first tick.
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        st.navGraph = BotNavigationGraphProvider.getGraph(map, profile);
        st.navEdge = null; // set per case
        BotPhysicsEngine.attachToRope(st, bot, rope, anchor.y);
        return st;
    }

    private static void holdEdge(BotMovementState st, BotNavigationGraph.Edge edge) {
        st.navEdge = edge;
        st.navTargetRegionId = edge.toRegionId;
    }

    /** Every launchStepX==0 CLIMB exit of every rope region on the real tower. */
    private static List<BotNavigationGraph.Edge> ropeExits(BotNavigationGraph g) throws Exception {
        var f = BotNavigationGraph.class.getDeclaredField("outgoingByRegionId");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        var outgoing = (java.util.Map<Integer, List<BotNavigationGraph.Edge>>) f.get(g);
        List<BotNavigationGraph.Edge> out = new ArrayList<>();
        for (var e : outgoing.entrySet()) {
            var from = g.getRegion(e.getKey());
            if (from == null || !from.isRopeRegion) {
                continue;
            }
            for (BotNavigationGraph.Edge edge : e.getValue()) {
                if (edge.type == BotNavigationGraph.EdgeType.CLIMB && edge.launchStepX == 0) {
                    out.add(edge);
                }
            }
        }
        return out;
    }

    private static Rope ropeOf(BotNavigationGraph g, MapleMap map, BotNavigationGraph.Edge edge) {
        return BotNavigationGraphProvider.findRopeFromRegion(map, g.getRegion(edge.fromRegionId));
    }

    /** isTopStepOffExit's geometry, mirrored to classify the enumerated shapes. */
    private static boolean isStepOff(Rope rope, BotNavigationGraph.Edge edge) {
        return edge.startPoint.y == rope.topY()
                && Math.abs(edge.endPoint.y - rope.topY()) <= BotMovementManager.cfg.JUMP_Y_THRESH * 2;
    }

    /** Whether physics already lands this edge's target (the no-jump shape). */
    private static boolean physicsLandsTarget(BotNavigationGraph g, MapleMap map, Rope rope,
                                              BotNavigationGraph.Edge edge) {
        Point landing = BotPhysicsEngine.findTopExitLanding(map, rope);
        Foothold fh = landing == null ? null : BotPhysicsEngine.findGroundFoothold(map, landing);
        return fh != null && g.regionIdByFootholdId.getOrDefault(fh.getId(), -1) == edge.toRegionId;
    }

    private record Shape(BotNavigationGraph.Edge edge, Rope rope, BotMovementProfile profile) {
    }

    private enum Branch { BALLISTIC, STEP_OFF, CLIMB_DOWN, PHYSICS_LANDS }

    /** First enumerated shape of the requested branch class, with its rope. */
    private static Shape findShape(BotNavigationGraph g, MapleMap map, Branch branch) throws Exception {
        for (BotMovementProfile profile : profiles()) {
            BotNavigationGraph pg = BotNavigationGraphProvider.getGraph(map, profile);
            for (BotNavigationGraph.Edge edge : ropeExits(pg)) {
                Rope rope = ropeOf(pg, map, edge);
                if (rope == null) {
                    continue;
                }
                boolean stepOff = isStepOff(rope, edge);
                Branch actual;
                if (stepOff) {
                    actual = Branch.STEP_OFF;
                } else if (edge.endPoint.y >= edge.startPoint.y) {
                    actual = Branch.CLIMB_DOWN;
                } else if (edge.startPoint.x != edge.endPoint.x) {
                    continue; // not a rope-head exit shape
                } else if (physicsLandsTarget(pg, map, rope, edge)) {
                    actual = Branch.PHYSICS_LANDS;
                } else {
                    actual = Branch.BALLISTIC;
                }
                if (actual == branch) {
                    return new Shape(edge, rope, profile);
                }
            }
        }
        return null;
    }

    /**
     * The profiles the tower mints the fire shape under: the ballistic rope-head exit exists only
     * for the taller jump arcs (the height-classified per-column validation), so the search runs
     * the base profile first and the calibrated thief-grade ones after it.
     */
    private static List<BotMovementProfile> profiles() {
        return List.of(BotMovementProfile.base(),
                new BotMovementProfile(105, 110), new BotMovementProfile(120, 115));
    }

    /** Drives the production executor for {@code shape} and returns the state after the attempt. */
    private static BotMovementState drive(MapleMap map, Shape shape) {
        BotMovementState st = climbingStateAtAnchor(map, shape.rope(), shape.profile(),
                new Point(shape.rope().x(), shape.edge().startPoint.y));
        holdEdge(st, shape.edge());
        BotNavigationManager.resolveTarget(st, new Point(shape.edge().endPoint), true);
        return st;
    }

    @Test
    void ballisticFireExitRecordsTheFlight() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        Shape shape = findShape(g, map, Branch.BALLISTIC);
        assertNotNull(shape, "the real tower must carry a ballistic straight-up rope exit (fb4cb83)");

        BotMovementState st = drive(map, shape);

        assertTrue(st.inAir, "precondition: the ballistic exit actually fired off the rope");
        assertTrue(st.fixedAirArc, "precondition: the fired arc is pinned to the authored trajectory");
        assertSame(shape.edge(), st.navFlightEdge,
                "the fired ballistic arc must record its edge: a mid-air replan must not steer "
                        + "the bot off the authored landing path");
    }

    @Test
    void topStepOffExitRecordsTheFlight() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        Shape shape = findShape(g, map, Branch.STEP_OFF);
        assertNotNull(shape, "the real tower must carry a top step-off exit");

        BotMovementState st = drive(map, shape);

        assertSame(shape.edge(), st.navFlightEdge,
                "the physics-owned top step-off must record its edge: the fall out of the top "
                        + "boundary belongs to this edge and must survive a mid-fall replan");
    }

    @Test
    void climbDownExitRecordsTheFlight() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        Shape shape = findShape(g, map, Branch.CLIMB_DOWN);
        assertNotNull(shape, "the real tower must carry a downward rope exit (climb-down delegation)");

        BotMovementState st = drive(map, shape);

        assertSame(shape.edge(), st.navFlightEdge,
                "the physics-owned climb-down must record its edge: the descent and its bottom "
                        + "drop belong to this edge");
    }

    @Test
    void physicsLandsTheTargetExitRecordsTheFlight() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        Shape shape = findShape(g, map, Branch.PHYSICS_LANDS);
        assertNotNull(shape,
                "the real tower must carry a physics-lands-the-target exit (Eos's r31->r15 shape)");

        BotMovementState st = drive(map, shape);

        assertSame(shape.edge(), st.navFlightEdge,
                "an exit physics already lands must still record the edge: no jump is fired, but "
                        + "the flight (the physics landing) belongs to it");
    }

    /**
     * The DIRECTIONAL jump-off exit (launchStepX != 0): the executor jumps off the rope toward the
     * authored landing side and the record must be written at the launch site.
     */
    @Test
    void directionalJumpOffExitRecordsTheFlight() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        BotNavigationGraph.Edge shape = null;
        Rope rope = null;
        for (BotNavigationGraph.Edge edge : directionalExits(g)) {
            Rope candidate = ropeOf(g, map, edge);
            if (candidate != null) {
                shape = edge;
                rope = candidate;
                break;
            }
        }
        assertNotNull(shape, "the real tower must carry directional rope exits (the LPQ tower)");

        BotMovementState st = climbingStateAtAnchor(map, rope, new Point(rope.x(), shape.startPoint.y));
        holdEdge(st, shape);
        BotNavigationManager.resolveTarget(st, new Point(shape.endPoint), true);

        assertTrue(st.inAir, "precondition: the directional exit actually fired off the rope");
        assertTrue(st.fixedAirArc,
                "the fired directional arc must be pinned (fixedAirArc) to the authored trajectory");
        assertSame(shape, st.navFlightEdge,
                "the fired directional arc must record its edge (reuseCommittedEdge retention)");
    }

    /** Every launchStepX!=0 CLIMB exit of every rope region on the real tower. */
    private static List<BotNavigationGraph.Edge> directionalExits(BotNavigationGraph g) throws Exception {
        var f = BotNavigationGraph.class.getDeclaredField("outgoingByRegionId");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        var outgoing = (java.util.Map<Integer, List<BotNavigationGraph.Edge>>) f.get(g);
        List<BotNavigationGraph.Edge> out = new ArrayList<>();
        for (var e : outgoing.entrySet()) {
            var from = g.getRegion(e.getKey());
            if (from == null || !from.isRopeRegion) {
                continue;
            }
            for (BotNavigationGraph.Edge edge : e.getValue()) {
                if (edge.type == BotNavigationGraph.EdgeType.CLIMB && edge.launchStepX != 0) {
                    out.add(edge);
                }
            }
        }
        return out;
    }

    /**
     * The reader side at the integration level: after a real physics-owned launch, the recorded
     * edge survives {@code reuseCommittedEdge} for the fall out of the boundary - and stops being
     * retained the moment the flight fact is cleared (the landing path clears it).
     */
    @Test
    void aRecordedLaunchSurvivesReuseAndDiesOnClearing() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        Shape shape = findShape(g, map, Branch.CLIMB_DOWN);
        assertNotNull(shape, "the real tower must carry a downward rope exit");

        BotMovementState st = drive(map, shape);
        assertSame(shape.edge(), st.navFlightEdge, "precondition: the launch recorded the edge");

        st.climbing = false;
        st.inAir = true; // the boundary fall: the edge is now flying
        BotNavigationGraph.Edge kept =
                BotNavigationManager.reuseCommittedEdge(g, st, -1, shape.edge().toRegionId);
        assertSame(shape.edge(), kept, "the recorded flight must be retained while it flies");

        st.navFlightEdge = null; // BotPhysicsEngine.landOnGround clears it on touch-down
        BotNavigationGraph.Edge dropped =
                BotNavigationManager.reuseCommittedEdge(g, st, -1, shape.edge().toRegionId);
        assertNull(dropped, "without the flight fact the edge must be replannable again");
    }
}
