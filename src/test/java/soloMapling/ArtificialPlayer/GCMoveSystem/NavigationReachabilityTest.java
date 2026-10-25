package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.manager.ServerManager;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Rope;
import org.gms.net.packet.Packet;
import org.gms.net.server.coordinator.world.MonsterAggroCoordinator;
import org.junit.Test;
import org.junit.BeforeClass;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class NavigationReachabilityTest {
    private static final AtomicInteger MAP_IDS = new AtomicInteger(990000000);

    @BeforeClass
    public static void initializeCharacterServices() throws Exception {
        // Character's static service references require a context, not a running world/DB.
        try (var server = mockStatic(ServerManager.class)) {
            ApplicationContext context = mock(ApplicationContext.class);
            server.when(ServerManager::getApplicationContext).thenReturn(context);
            Class.forName(Character.class.getName());
        }
    }

    @Test
    public void jumpOntoThreePixelShelfIsNotRejectedForContinuingToWalk() throws Exception {
        Foothold base = floor(1, -120, 0, 0);
        Foothold shelf = floor(2, 20, 23, -50);
        MapleMap map = geometry(base, shelf);
        BotNavigationGraph graph = build(map);
        int from = region(graph, base);
        int to = region(graph, shelf);
        assertTrue("the physics permits a landing", hasJumpLanding(map, base, shelf));
        assertTrue("a landable narrow shelf needs an inbound jump", graph.getOutgoing(from).stream()
                .anyMatch(edge -> edge.toRegionId == to && edge.type == BotNavigationGraph.EdgeType.JUMP));
    }

    @Test
    public void threePixelShelfCanDownJump() throws Exception {
        Foothold shelf = floor(1, 0, 3, 0);
        Foothold base = floor(2, -100, 100, 100);
        MapleMap map = geometry(shelf, base);
        BotNavigationGraph graph = build(map);
        assertNotNull(BotPhysicsEngine.simulateDownJumpLanding(map, new Point(1, 0)));
        assertTrue("endpoints are legal down-jump positions, even on tiny platforms",
                graph.getOutgoing(region(graph, shelf)).stream().anyMatch(edge ->
                        edge.toRegionId == region(graph, base) && edge.type == BotNavigationGraph.EdgeType.DROP
                                && edge.launchStepX == 0 && edge.containsLaunchX(1)));
    }

    @Test
    public void everyNarrowLandingBelowWideShelfHasItsOwnDropWindow() throws Exception {
        Foothold upper = floor(1, -150, 150, 0);
        Foothold lower = floor(2, 37, 40, 100);
        Foothold bottom = floor(3, -300, 300, 180);
        MapleMap map = geometry(upper, lower, bottom);
        BotNavigationGraph graph = build(map);
        assertTrue(graph.getOutgoing(region(graph, upper)).stream().anyMatch(edge ->
                edge.toRegionId == region(graph, lower) && edge.type == BotNavigationGraph.EdgeType.DROP
                        && edge.launchStepX == 0));
        for (BotNavigationGraph.Edge edge : graph.getOutgoing(region(graph, upper))) {
            if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX != 0) {
                continue;
            }
            for (int x = edge.launchMinX; x <= edge.launchMaxX; x++) {
                BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateDownJumpLanding(map, new Point(x, 0));
                assertNotNull(landing);
                assertEquals("a drop window cannot bridge a different landing at x=" + x,
                        edge.toRegionId, region(graph, landing.foothold()));
            }
        }
    }

    @Test
    public void deepDropIsReachableEvenWhenAnotherShallowExitExists() throws Exception {
        Foothold upper = floor(1, -100, 100, 0);
        Foothold shallow = floor(2, -100, -20, 100);
        Foothold deep = floor(3, 20, 100, 500);
        MapleMap map = geometry(upper, shallow, deep);
        BotNavigationGraph graph = build(map);
        assertTrue("a descent is not invalid merely because another exit is shorter",
                graph.getOutgoing(region(graph, upper)).stream()
                        .anyMatch(edge -> edge.toRegionId == region(graph, deep)));
    }

    @Test
    public void ballisticVerticalRopeExitApproachesItsLaunchHeight() throws Exception {
        Foothold base = floor(1, -100, 100, 100);
        MapleMap map = geometry(base);
        Rope rope = new Rope(0, -100, 100, true);
        map.addRope(rope);
        BotNavigationGraph graph = build(map);
        Character bot = character(map, new Point(0, 40));
        BotMovementState state = new BotMovementState(bot, null);
        state.climbing = true;
        state.climbRope = rope;
        int ropeRegion = graph.regions.stream().filter(r -> r.isRopeRegion).findFirst().orElseThrow().id;
        BotNavigationGraph.Edge exit = new BotNavigationGraph.Edge(ropeRegion, region(graph, base),
                BotNavigationGraph.EdgeType.CLIMB, new Point(0, 0), new Point(0, 100), 0, 0,
                rope.x(), rope.topY(), rope.bottomY(), 400);
        assertEquals("a ballistic exit must not climb toward its landing instead of its takeoff",
                exit.startPoint, BotNavigationManager.selectClimbWaypoint(graph, state, bot.getPosition(), exit));
    }

    @Test
    public void preciseApproachCanMoveOnePixelOnThreePixelPlatform() throws Exception {
        Foothold shelf = floor(1, 0, 3, 0);
        MapleMap map = geometry(shelf);
        build(map);
        Character bot = character(map, new Point(0, 0));
        BotMovementState state = new BotMovementState(bot, null);
        state.movementProfile = BotMovementProfile.base();
        state.physX = 0;
        state.navPreciseTarget = true;
        state.navEdge = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(1, 0), new Point(30, -30), 1, 1, 7, 0, 0, 0, 0, 500);
        BotMovementManager.tickGrounded(state, new Point(1, 0));
        assertEquals("a full-speed preview must not veto a safe precision approach", 1, bot.getPosition().x);
        assertFalse(state.inAir);
    }

    @Test
    public void ropeApproachDoesNotStopOnePixelOutsideGrip() throws Exception {
        Foothold base = floor(1, 80, 140, 0);
        MapleMap map = geometry(base);
        Rope rope = new Rope(114, -100, 50, true);
        map.addRope(rope);
        BotNavigationGraph graph = build(map);
        int ropeRegion = graph.regions.stream().filter(r -> r.isRopeRegion).findFirst().orElseThrow().id;
        Character bot = character(map, new Point(105, 0));
        BotMovementState state = new BotMovementState(bot, null);
        state.physX = 105;
        state.navPreciseTarget = true;
        state.navEdge = new BotNavigationGraph.Edge(region(graph, base), ropeRegion,
                BotNavigationGraph.EdgeType.CLIMB, new Point(106, 0), new Point(114, 0),
                0, 0, 0, 0, 0, 50);
        BotMovementManager.tickGrounded(state, new Point(106, 0));
        assertEquals("grip starts at x=106; stopping at x=105 is not arrival", 106, bot.getPosition().x);
    }

    @Test
    public void trueDeadPitIsNeverAPathDestination() throws Exception {
        Foothold upper = floor(1, -150, 150, 0);
        Foothold pit = floor(2, -150, 150, 400);
        MapleMap map = geometry(upper, pit);
        when(map.getPortals().iterator().next().getTargetMapId()).thenReturn(999999999);
        BotNavigationGraph graph = build(map);
        assertNotNull("physics can land there, but the landing is a trap",
                BotPhysicsEngine.simulateDownJumpLanding(map, new Point(0, 0)));
        assertFalse("BFS and other graph consumers must also exclude traps", graph.getOutgoing(region(graph, upper))
                .stream().anyMatch(e -> e.toRegionId == region(graph, pit)));
        assertTrue(graph.deadRegionIds.contains(region(graph, pit)));
        assertNull(BotNavigationManager.findPath(graph, map, new Point(0, 0),
                region(graph, upper), region(graph, pit), new Point(0, 400)));
    }

    @Test
    public void branchesWhoseOnlyExitIsADeadPitAreAlsoExcluded() throws Exception {
        Foothold branch = floor(1, -50, 50, 0), middle = floor(2, -50, 50, 400), bottom = floor(3, -50, 50, 800);
        MapleMap map = geometry(branch, middle, bottom,
                floor(4, 700, 800, -400)); // ordinary top ground, outside this dead branch
        when(map.getPortals().iterator().next().getTargetMapId()).thenReturn(999999999);
        BotNavigationGraph graph = build(map);
        assertEquals(java.util.Set.of(region(graph, branch), region(graph, middle), region(graph, bottom)), graph.deadRegionIds);
        assertTrue(graph.getOutgoing(region(graph, branch)).isEmpty());
    }

    @Test
    public void wallCollisionKeepsPredictedArcInsteadOfReapplyingOldAirKey() throws Exception {
        Foothold launch = floor(1, -100, 0, 0);
        Foothold base = floor(2, -100, 200, 150);
        Foothold top = floor(3, 20, 40, -100);
        Foothold right = new Foothold(new Point(40, -100), new Point(40, 50), 4);
        Foothold bottom = new Foothold(new Point(40, 50), new Point(20, 50), 5);
        Foothold left = new Foothold(new Point(20, 50), new Point(20, -100), 6);
        Foothold[] box = {top, right, bottom, left};
        for (int i = 0; i < box.length; i++) {
            box[i].setPrev(box[(i + box.length - 1) % box.length].getId());
            box[i].setNext(box[(i + 1) % box.length].getId());
        }
        MapleMap map = geometry(launch, base, top, right, bottom, left);
        int step = BotPhysicsEngine.walkStep(map);
        Point start = new Point(-5, 0);
        BotPhysicsEngine.JumpLanding predicted = BotPhysicsEngine.simulateJumpLanding(map, start, step);
        assertNotNull(predicted);
        Character bot = character(map, new Point(start));
        BotMovementState state = new BotMovementState(bot, null);
        state.navEdge = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                start, predicted.point(), step, 0, 0, 0, 0, 500);
        BotPhysicsEngine.beginGroundJump(state, bot, step);
        for (int tick = 0; tick < 100 && state.inAir; tick++) {
            BotMovementManager.tickAirborne(state, predicted.point());
        }
        assertFalse("old held key must not pin gravity against the wall", state.inAir);
        assertEquals(predicted.point(), bot.getPosition());
        assertSame(predicted.foothold(), state.groundFoothold);
    }

    @Test
    public void landingWithRealMapExitIsNotDead() throws Exception {
        Foothold upper = floor(1, -100, 100, 0);
        Foothold lower = floor(2, -100, 100, 500);
        MapleMap map = geometry(upper, lower);
        BotNavigationGraph graph = build(map);
        assertFalse(graph.deadRegionIds.contains(region(graph, lower)));
        assertNotNull(BotNavigationManager.findPath(graph, map, new Point(0, 0),
                region(graph, upper), region(graph, lower), new Point(0, 500)));
    }

    @Test
    public void portalRuntimeStatusDoesNotPoisonSharedGeometryCache() throws Exception {
        Foothold upper = floor(1, -100, 100, 0);
        Foothold lower = floor(2, -100, 100, 500);
        MapleMap map = geometry(upper, lower);
        when(map.getPortals().iterator().next().getPortalStatus()).thenReturn(false);
        assertFalse("a temporary instance lock is not a physical dead pit",
                build(map).deadRegionIds.contains(2));
    }

    @Test
    public void serializedGraphRetainsDeadRegionRestrictions() throws Exception {
        MapleMap map = geometry(floor(1, -40, 40, 0), floor(2, -40, 40, 500));
        when(map.getPortals().iterator().next().getTargetMapId()).thenReturn(999999999);
        BotNavigationGraph graph = build(map);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(graph);
        }
        try (var input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            BotNavigationGraph cached = (BotNavigationGraph) input.readObject();
            assertEquals(graph.deadRegionIds, cached.deadRegionIds);
            assertTrue(cached.getOutgoing(1).isEmpty());
        }
    }

    @Test
    public void spawnOnlyPortalDoesNotMakePitEscapable() throws Exception {
        Foothold upper = floor(1, -100, 100, 0);
        Foothold lower = floor(2, -100, 100, 500);
        MapleMap map = geometry(upper, lower);
        Portal spawn = map.getPortals().iterator().next();
        when(spawn.getTargetMapId()).thenReturn(999999999);
        assertTrue(build(map).deadRegionIds.contains(2));
    }

    @Test
    public void forbidFallDownDoesNotGainStraightDrop() throws Exception {
        Foothold upper = floor(1, -60, 60, 0);
        upper.setForbidFallDown(true);
        Foothold lower = floor(2, -100, 100, 500);
        MapleMap map = geometry(upper, lower);
        BotNavigationGraph graph = build(map);
        assertFalse(graph.getOutgoing(region(graph, upper)).stream()
                .anyMatch(e -> e.type == BotNavigationGraph.EdgeType.DROP && e.launchStepX == 0));
        assertTrue("forbidFallDown forbids down+jump, not walking off the ledge",
                graph.getOutgoing(region(graph, upper)).stream()
                        .anyMatch(e -> e.type == BotNavigationGraph.EdgeType.DROP && e.launchStepX != 0));
    }

    @Test
    public void slipperyLandingBrakePredictionMatchesExecution() throws Exception {
        Foothold upper = floor(1, -120, 0, 0);
        Foothold lower = floor(2, -120, 200, 100);
        MapleMap map = geometry(upper, lower);
        map.setFootholdSpeed(0.2f);
        int step = BotPhysicsEngine.walkStep(map);
        Point start = new Point(-20, 0);
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(map, start, step);
        BotPhysicsEngine.PostLandingJump stopped = BotPhysicsEngine.simulateLandingStop(map, landing, BotMovementProfile.base());
        assertFalse(stopped.lostGround());
        Character bot = character(map, new Point(start));
        BotMovementState state = new BotMovementState(bot, null);
        state.navEdge = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                start, landing.point(), step, 0, 0, 0, 0, 500);
        BotPhysicsEngine.beginGroundJump(state, bot, step);
        for (int tick = 0; tick < 100 && state.inAir; tick++) {
            BotMovementManager.tickAirborne(state, landing.point());
        }
        assertFalse(state.inAir);
        for (int tick = 0; tick < 100 && state.landingStopPending; tick++) {
            BotMovementManager.tickGrounded(state, null);
        }
        assertFalse(state.landingStopPending);
        assertEquals(stopped.finalPoint(), bot.getPosition());
        assertSame(stopped.finalFoothold(), state.groundFoothold);
        assertEquals(0.0, state.hspeed, 0.0);
    }

    @Test
    public void exactPixelApproachesConvergeAndStayStill() {
        for (int width : new int[]{1, 2, 3, 7, 19}) {
            Foothold shelf = floor(1, -width, 0, 0);
            MapleMap map = geometry(shelf);
            for (int target = -width; target <= 0; target++) {
                Point cursor = new Point(-width, 0);
                BotPhysicsEngine.GroundTravelState travel = BotPhysicsEngine.initialGroundTravelState(cursor);
                for (int tick = 0; tick < 80; tick++) {
                    BotPhysicsEngine.GroundStepResult step = BotPhysicsEngine.simulateGroundMotion(map, cursor,
                            shelf, 1, travel, BotMovementProfile.base(), new BotPhysicsEngine.GroundTarget(target, 0));
                    assertFalse("safe approach width=" + width + " target=" + target, step.lostGround());
                    cursor = step.point();
                    travel = step.state();
                    if (tick >= 40) {
                        assertEquals("no pixel-hunting limit cycle", target, cursor.x);
                        assertEquals(0.0, travel.hspeed(), 0.0);
                    }
                }
            }
        }
    }

    @Test
    public void allGeneratedJumpPixelsHaveStableLandingInClaimedRegion() throws Exception {
        Foothold base = floor(1, -100, 100, 0);
        Foothold left = floor(2, -31, -28, -40);
        Foothold right = floor(3, 35, 38, -65);
        MapleMap map = geometry(base, left, right);
        BotNavigationGraph graph = build(map);
        int checked = 0;
        for (BotNavigationGraph.Region source : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(source.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.JUMP) {
                    continue;
                }
                for (int x = edge.launchValidMinX; x <= edge.launchValidMaxX; x++) {
                    BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(map,
                            source.pointAt(x), edge.launchStepX);
                    assertNotNull(landing);
                    assertEquals(edge.toRegionId, region(graph, landing.foothold()));
                    BotPhysicsEngine.PostLandingJump stopped = BotPhysicsEngine.simulateLandingStop(map,
                            landing, BotMovementProfile.base());
                    assertFalse(stopped.lostGround());
                    assertEquals(edge.toRegionId, region(graph, stopped.finalFoothold()));
                    checked++;
                }
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    public void everyStablePhysicsJumpOntoLiveGroundHasGraphCoverage() throws Exception {
        MapleMap map = geometry(floor(1, -80, 80, 0), floor(2, -31, -28, -40),
                floor(3, 35, 38, -65), floor(4, -40, 40, 140));
        BotNavigationGraph graph = build(map);
        int covered = 0;
        int step = BotPhysicsEngine.walkStep(map);
        for (BotNavigationGraph.Region source : graph.regions) {
            for (int x = source.minX + 1; x < source.maxX; x++) {
                Point start = source.pointAt(x);
                for (int speed : new int[]{-step, 0, step}) {
                    BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(map, start, speed);
                    BotPhysicsEngine.PostLandingJump stop = BotPhysicsEngine.simulateLandingStop(map, landing, graph.movementProfile);
                    if (stop == null || stop.lostGround()) {
                        continue;
                    }
                    int to = region(graph, landing.foothold());
                    if (to == source.id || to != region(graph, stop.finalFoothold()) || graph.deadRegionIds.contains(to)) {
                        continue;
                    }
                    int launchX = x;
                    assertTrue("missing physical jump R" + source.id + "->R" + to + " at x=" + x,
                            graph.getOutgoing(source.id).stream().anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                    && edge.toRegionId == to && edge.launchStepX == speed
                                    && launchX >= edge.launchValidMinX && launchX <= edge.launchValidMaxX));
                    covered++;
                }
            }
        }
        assertTrue(covered > 0);
    }

    @Test
    public void navigationActuallyJumpsAndSettlesOnTinyUpperShelf() throws Exception {
        Foothold base = floor(1, -120, 0, 0);
        Foothold shelf = floor(2, 20, 23, -50);
        MapleMap map = geometry(base, shelf);
        BotNavigationGraph graph = build(map);
        runTo(map, graph, new Point(-100, 0), new Point(21, -50), region(graph, shelf));
    }

    @Test
    public void navigationClimbsAndJumpsOffWithoutReversingOnLadder() throws Exception {
        Foothold base = floor(1, -80, 80, 150);
        Foothold upper = floor(2, 30, 33, -65);
        MapleMap map = geometry(base, upper);
        map.addRope(new Rope(0, -100, 150, true));
        BotNavigationGraph graph = build(map);
        assertNotNull(BotNavigationManager.findPath(graph, map, new Point(0, 150),
                region(graph, base), region(graph, upper), new Point(31, -65)));
        runTo(map, graph, new Point(0, 150), new Point(31, -65), region(graph, upper));
    }

    @Test
    public void verticalRopeGrabRetainsItsFlightEdgeUntilAttachment() throws Exception {
        Foothold base = floor(1, -100, 100, 0);
        MapleMap map = geometry(base);
        Rope rope = new Rope(0, -150, -30, true);
        map.addRope(rope);
        BotNavigationGraph graph = build(map);
        int ropeRegion = graph.regions.stream().filter(r -> r.isRopeRegion).findFirst().orElseThrow().id;
        BotNavigationGraph.Edge edge = graph.getOutgoing(region(graph, base)).stream()
                .filter(e -> e.type == BotNavigationGraph.EdgeType.CLIMB && e.toRegionId == ropeRegion && e.launchStepX == 0)
                .findFirst().orElseThrow();
        Character bot = character(map, new Point(edge.startPoint));
        BotMovementState state = new BotMovementState(bot, null);
        state.physX = edge.startPoint.x;
        state.navGraph = graph;
        state.navEdge = edge;
        state.navTargetRegionId = ropeRegion;
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, graph.movementProfile)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, graph.movementProfile)).thenReturn(graph);
            assertTrue(BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(state, new Point(0, -100)));
            assertSame(edge, state.navFlightEdge);
            assertSame(edge, BotNavigationManager.reuseCommittedEdge(graph, state, -1, ropeRegion));
            for (int tick = 0; tick < 50 && state.inAir; tick++) {
                BotMovementManager.tickAirborne(state, new Point(0, -100));
            }
            assertTrue(state.climbing);
            assertEquals(rope, state.climbRope);
            assertNull(state.navFlightEdge);
        }
    }

    static void runTo(MapleMap map, BotNavigationGraph graph, Point start, Point target,
                              int targetRegion) throws Exception {
        Character bot = character(map, start);
        BotMovementState state = new BotMovementState(bot, null);
        state.movementProfile = graph.movementProfile;
        state.physX = start.x;
        state.physY = start.y;
        state.navGraph = graph;
        state.moveTarget = target;
        state.moveTargetPrecise = true;
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            state.fhIndex.put(fh.getId(), fh);
        }
        MovementClock.useVirtual(10000);
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, graph.movementProfile)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, graph.movementProfile)).thenReturn(graph);
            StringBuilder trace = new StringBuilder();
            Point previous = new Point(start);
            for (int tick = 0; tick < 1600; tick++) {
                MovementClock.advance(50);
                BotNavigationManager.NavigationDirective nav = BotNavigationManager.resolveTarget(state, target, tick % 2 == 0);
                if (!nav.consumedTick) {
                    if (state.inAir) {
                        BotMovementManager.tickAirborne(state, nav.targetPos);
                    } else if (state.climbing) {
                        BotMovementManager.tickClimbing(state, nav.targetPos, tick % 2 == 0);
                    } else {
                        BotMovementManager.tickGrounded(state, nav.targetPos);
                    }
                    if (tick % 2 == 0 && !state.inAir && !state.climbing) {
                        BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(state, target);
                    }
                }
                Point actual = bot.getPosition();
                assertTrue("execution must not teleport to pass the route: " + previous + " -> " + actual,
                        Math.abs(actual.x - previous.x) <= 30 && Math.abs(actual.y - previous.y) <= 70);
                previous = actual;
                if (tick % 40 == 0 || tick >= 1520) {
                    BotNavigationGraph.Edge e = state.navEdge;
                    trace.append("\n").append(tick).append(" ").append(bot.getPosition())
                            .append(" air=").append(state.inAir).append(" climb=").append(state.climbing)
                            .append(" precise=").append(state.navPreciseTarget).append(" target=").append(nav.targetPos)
                            .append(" v=").append(state.airVelX).append(",").append(state.velY)
                            .append(" stop=").append(state.landingStopPending)
                            .append(" physical=").append(state.physX).append(",").append(state.physY)
                            .append(" ground=").append(state.groundFoothold == null ? "none" : state.groundFoothold.getId())
                            .append(" edge=").append(e == null ? "none" : e.type + " R" + e.fromRegionId + "->" + e.toRegionId
                                    + " " + e.startPoint + " -> " + e.endPoint + " vx=" + e.launchStepX)
                            .append(" gate=").append(state.lastEdgeBlockReason);
                }
                if (!state.inAir && !state.climbing && !state.landingStopPending
                        && BotNavigationManager.resolveCurrentRegionId(graph, state, map, bot.getPosition()) == targetRegion) {
                    return;
                }
            }
            fail("did not arrive: pos=" + bot.getPosition() + " edge=" + state.navEdge
                    + " decision=" + state.lastNavDecision + " gate=" + state.lastEdgeBlockReason + trace);
        } finally {
            MovementClock.reset();
        }
    }

    static Foothold floor(int id, int left, int right, int y) {
        return new Foothold(new Point(left, y), new Point(right, y), id);
    }

    static MapleMap geometry(Foothold... footholds) {
        MapleMap map = geometryMap(MAP_IDS.incrementAndGet());
        FootholdTree tree = new FootholdTree(new Point(-1000, -1000), new Point(1000, 2000));
        for (Foothold foothold : footholds) {
            tree.insert(foothold);
        }
        map.setFootholds(tree);
        map.setMapLineBoundings(-1000, 2000, -1000, 1000);
        // The lowest floor has an actual map exit; trap tests explicitly remove it.
        Foothold lowest = java.util.Arrays.stream(footholds).filter(f -> !f.isWall())
                .max(java.util.Comparator.comparingInt(Foothold::getY1)).orElseThrow();
        Portal exit = mock(Portal.class);
        when(exit.getPosition()).thenReturn(new Point((lowest.getX1() + lowest.getX2()) / 2, lowest.getY1()));
        when(exit.getTargetMapId()).thenReturn(100000000);
        when(exit.getPortalStatus()).thenReturn(true);
        map.addPortal(exit);
        return map;
    }

    static final class GeometryMap extends MapleMap {
        GeometryMap(int id) {
            super(id, 0, 0, id, 0f);
        }

        @Override
        public void broadcastMessage(Character bot, Packet packet, boolean repeatToSource) {
            // Packet production is exercised; there are deliberately no network clients.
        }
    }

    static MapleMap geometryMap(int id) {
        // The host map constructor creates an aggro service tied to Server's singleton.
        // Geometry is real; only that unrelated service is replaced.
        try (var aggro = mockConstruction(MonsterAggroCoordinator.class)) {
            return new GeometryMap(id);
        }
    }

    static Character character(MapleMap map, Point position) {
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenAnswer(ignored -> new Point(position));
        doAnswer(call -> {
            position.setLocation((Point) call.getArgument(0));
            return null;
        }).when(bot).setPosition(any(Point.class));
        return bot;
    }

    static BotNavigationGraph build(MapleMap map) throws Exception {
        return build(map, BotMovementProfile.base());
    }

    static BotNavigationGraph build(MapleMap map, BotMovementProfile profile) throws Exception {
        Method method = BotNavigationGraphProvider.class.getDeclaredMethod("buildGraph", MapleMap.class,
                BotMovementProfile.class);
        method.setAccessible(true);
        return (BotNavigationGraph) method.invoke(null, map, profile);
    }

    static int region(BotNavigationGraph graph, Foothold foothold) {
        return graph.regionIdByFootholdId.get(foothold.getId());
    }

    private static boolean hasJumpLanding(MapleMap map, Foothold from, Foothold to) {
        int step = BotPhysicsEngine.walkStep(map);
        for (int x = from.getX1(); x <= from.getX2(); x++) {
            BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(map,
                    new Point(x, from.getY1()), step);
            if (landing != null && landing.foothold() == to) {
                return true;
            }
        }
        return false;
    }
}
