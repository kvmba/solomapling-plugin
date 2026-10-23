package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GroundApproachExecutionTest {
    private static int nextMapId = 922018000;

    @BeforeAll
    static void boot() throws Exception {
        LpqFallbackRopeApproachTest.stubHostEnvironment();
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    private static MapleMap floor(int width, float slip) {
        MapleMap map = new MapleMap(nextMapId++, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-500, 600, -1000, 1000);
        map.setFootholdSpeed(slip);
        FootholdTree tree = new FootholdTree(new Point(-1000, -500), new Point(1000, 600));
        tree.insert(new Foothold(new Point(-width / 2, 130), new Point(width / 2, 130), 1));
        map.setFootholds(tree);
        return map;
    }

    private static BotMovementState state(MapleMap map, int x, int speed) {
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        Point position = new Point(x, 130);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getId()).thenReturn(map.getId());
        when(bot.getHp()).thenReturn(50);
        when(bot.getPosition()).thenAnswer(inv -> new Point(position));
        doAnswer(inv -> { position.setLocation((Point) inv.getArgument(0)); return null; })
                .when(bot).setPosition(any(Point.class));
        BotMovementState state = new BotMovementState(bot, null);
        state.lastMap = map;
        state.fhIndex = BotMovementManager.buildFhIndex(map);
        state.movementProfile = new BotMovementProfile(speed, 100);
        state.physX = x;
        state.physY = 130;
        state.groundFoothold = state.fhIndex.get(1);
        return state;
    }

    private static void approach(BotMovementState state, int target, int radius) {
        state.moveDir = Integer.compare(target, state.bot.getPosition().x);
        BotPhysicsEngine.applyGroundMotion(state, state.bot, state.groundFoothold,
                new BotPhysicsEngine.GroundTarget(target, radius));
    }

    @Test
    void fullTickPulseLimitCycleConvergesWithoutWideningTheBand() {
        MapleMap map = floor(60, 1.0f);
        BotMovementState state = state(map, -29, 200);
        for (int i = 0; i < 160; i++) approach(state, 2, 4);
        int settled = state.bot.getPosition().x;
        assertTrue(Math.abs(settled - 2) <= 4, "stopped outside the actual band: " + settled);
        assertEquals(0.0, state.hspeed);
        for (int i = 0; i < 40; i++) {
            approach(state, 2, 4);
            assertEquals(settled, state.bot.getPosition().x, "restarted after settling");
        }
    }

    @Test
    void realIntegratorSettlesAcrossSpeedsOffsetsAndSlipperiness() {
        for (float slip : new float[]{1.0f, 0.2f, 0.05f}) {
            MapleMap map = floor(1600, slip);
            for (int speed : new int[]{95, 100, 125, 160, 180, 200}) {
                for (int radius : new int[]{0, 1, 4, 8, 30}) {
                    for (int start : new int[]{-120, -29, -6, -1, 1, 6, 29, 120}) {
                        BotMovementState state = state(map, start, speed);
                        for (int i = 0; i < 180; i++) approach(state, 2, radius);
                        String scenario = "fs=" + slip + " speed=" + speed + " r=" + radius + " x=" + start;
                        assertFalse(state.inAir, scenario);
                        assertEquals(0.0, state.hspeed, scenario);
                        int settled = state.bot.getPosition().x;
                        assertTrue(Math.abs(settled - 2) <= radius, scenario + " parked=" + settled);
                        for (int i = 0; i < 8; i++) approach(state, 2, radius);
                        assertEquals(settled, state.bot.getPosition().x, scenario);
                    }
                }
            }
        }
    }

    @Test
    void predictionAndExecutionCarryIdenticalFractionalState() {
        for (float slip : new float[]{1.0f, 0.2f}) {
            MapleMap map = floor(1600, slip);
            BotMovementState state = state(map, -29, 200);
            for (int i = 0; i < 30; i++) {
                Point before = state.bot.getPosition();
                var target = new BotPhysicsEngine.GroundTarget(2, 1);
                var predicted = BotPhysicsEngine.simulateGroundMotion(map, before, state.groundFoothold, 1,
                        new BotPhysicsEngine.GroundTravelState(state.physX, state.hspeed, state.groundPhysicsCarryMs),
                        state.movementProfile, target);
                approach(state, 2, 1);
                assertEquals(predicted.point(), state.bot.getPosition());
                assertEquals(predicted.state().physX(), state.physX);
                assertEquals(predicted.state().hspeed(), state.hspeed);
                assertEquals(predicted.state().carryMs(), state.groundPhysicsCarryMs);
            }
        }
    }

    @Test
    void noLocalAscentWaitsWithoutChasingTheUpstairsXOrClaimingArrival() {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
        BotMovementState state = state(map, -29, 200);
        state.graphWarmupFallback = true;
        state.moveTarget = new Point(2, 60);
        state.moveTargetPrecise = true;
        for (int i = 0; i < 160; i++) {
            state.navPreciseTarget = true;
            BotMovementManager.tickGrounded(state, state.moveTarget);
            GCMovementDriver.clearReachedMoveTarget(state);
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            assertEquals(new Point(-29, 130), state.bot.getPosition());
            assertNotNull(state.moveTarget, "waiting is not arrival");
        }
        assertEquals("graph-warmup-no-local-route", state.lastNavDecision);
        assertNotNull(state.fallbackApproach);
        assertNull(state.fallbackApproach.launch());
        assertNull(state.navTargetPos);
        state.moveTargetPrecise = false;
        GCMovementDriver.clearReachedMoveTarget(state);
        assertNotNull(state.moveTarget, "the generous follow band cannot complete a no-route wait");
    }

    @Test
    void sameFloorFallbackAndSinglePixelLaunchStillProgress() {
        for (int speed : new int[]{100, 160, 200}) {
            BotMovementState state = state(floor(60, 1.0f), -29, speed);
            state.graphWarmupFallback = true;
            state.navPreciseTarget = true;
            for (int i = 0; i < 80; i++) BotMovementManager.tickGrounded(state, new Point(2, 130));
            assertTrue(Math.abs(state.bot.getPosition().x - 2) <= BotPhysicsEngine.walkStep(state.bot.getMap(), state.movementProfile));
            assertFalse(state.inAir);
            state = state(floor(60, 1.0f), -29, speed);
            for (int i = 0; i < 80; i++) approach(state, 2, 0);
            assertEquals(2, state.bot.getPosition().x, "one-pixel launch point must be reachable");
        }
    }

    private static BotNavigationGraph graph(MapleMap map, boolean crossing) {
        Foothold lower = map.getFootholds().getAllFootholds().stream().filter(f -> f.getId() == 1).findFirst().orElseThrow();
        Foothold upper = map.getFootholds().getAllFootholds().stream().filter(f -> f.getId() == 2).findFirst().orElseThrow();
        var r1 = new BotNavigationGraph.Region(1, List.of(new BotNavigationGraph.Segment(lower)));
        var r2 = new BotNavigationGraph.Region(2, List.of(new BotNavigationGraph.Segment(upper)));
        var jump = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(2, 130), new Point(2, 60), 2, 2, 0, 0, 0, 0, 0, 500);
        return new BotNavigationGraph(map.getId(), 1, BotMovementProfile.base(), List.of(r1, r2),
                Map.of(1, r1, 2, r2), Map.of(1, 1, 2, 2),
                crossing ? Map.of(1, List.of(jump)) : Map.of(), Set.of());
    }

    private static void coreTick(BotMovementState state, boolean ai) throws Exception {
        var method = GCMovementDriver.class.getDeclaredMethod("stepMovementCore", BotMovementState.class, Point.class, boolean.class);
        method.setAccessible(true);
        method.invoke(null, state, state.moveTarget != null ? state.moveTarget : state.bot.getPosition(), ai);
        MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
    }

    @Test
    void aGraphBecomingReadyResumesTheWaitingMoveAndExecutesTheJump() throws Exception {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
        BotMovementState state = state(map, -29, 100);
        state.moveTarget = new Point(2, 60);
        state.moveTargetPrecise = true;
        BotNavigationGraph graph = graph(map, true);
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(null);
            provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(map, state.movementProfile))
                    .thenReturn(new CompletableFuture<>());
            for (int i = 0; i < 10; i++) coreTick(state, i % 2 == 0);
            assertNotNull(state.moveTarget);
            assertEquals(-29, state.bot.getPosition().x);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(graph);
            for (int i = 0; i < 120 && state.moveTarget != null; i++) coreTick(state, i % 2 == 0);
            assertNull(state.moveTarget, "the ready graph must finish the same move");
            assertEquals(60, state.bot.getPosition().y);
            assertFalse(state.inAir);
            assertFalse(state.graphWarmupFallback);
            assertNull(state.fallbackApproach);
        }
    }

    @Test
    void anExactGraphWithNoCrossingFailsInsteadOfWalkingUnderTheTarget() throws Exception {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
        BotMovementState state = state(map, -29, 100);
        state.moveTarget = new Point(2, 60);
        state.moveTargetPrecise = true;
        org.gms.server.maps.Portal exit = mock(org.gms.server.maps.Portal.class);
        when(exit.getPosition()).thenReturn(new Point(-29, 130));
        when(exit.getId()).thenReturn(7);
        map.addPortal(exit);
        BotNavigationGraph graph = graph(map, false);
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
             var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(graph);
            coreTick(state, false);
            assertNotNull(state.moveTarget, "a physics-only tick has not searched yet");
            assertEquals(-29, state.bot.getPosition().x);
            coreTick(state, true);
            assertNull(state.moveTarget);
            movement.verify(() -> GCMovement.abandonMove(state));
            movement.verify(() -> GCMovement.fireArrival(state), never());
            assertEquals(-29, state.bot.getPosition().x);
        }
    }

    @Test
    void aCommittedWalkOffStillLeavesThePlatformWithMomentum() {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(30, 200), new Point(300, 200), 2));
        BotMovementState state = state(map, 0, 100);
        state.navEdge = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.DROP,
                new Point(29, 130), new Point(100, 200), 6, 0, 0, 0, 0, 500);
        state.navPreciseTarget = true;
        for (int i = 0; i < 40 && !state.inAir; i++) BotMovementManager.tickGrounded(state, new Point(29, 130));
        assertTrue(state.inAir, "walk-off must not brake short of its ledge");
        assertTrue(state.airVelX > 0);
    }

    @Test
    void slopedStitchedGroundAndRuntimeSpeedChangesConverge() {
        MapleMap map = floor(200, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-300, -500), new Point(300, 600));
        Foothold left = new Foothold(new Point(-100, 150), new Point(0, 130), 1);
        Foothold right = new Foothold(new Point(0, 130), new Point(100, 110), 2);
        left.setNext(2);
        right.setPrev(1);
        tree.insert(left);
        tree.insert(right);
        map.setFootholds(tree);
        BotMovementState state = state(map, 0, 200);
        state.debuffMoveScale = 0.5;
        for (int i = 0; i < 60; i++) approach(state, 80, 1);
        state.debuffMoveScale = 1.0;
        state.dashSpeedBonus = 40;
        for (int i = 0; i < 80; i++) approach(state, -80, 1);
        assertFalse(state.inAir);
        assertEquals(0.0, state.hspeed);
        assertTrue(Math.abs(state.bot.getPosition().x + 80) <= 1);
    }

    @Test
    void stoppingBandsAtTheLipCannotAuthorizeAPlatformFall() {
        for (float slip : new float[]{1.0f, 0.2f}) {
            for (int speed : new int[]{100, 160, 200}) {
                for (int target : new int[]{-30, -29, 29, 30}) {
                    MapleMap map = floor(60, slip);
                    map.getFootholds().insert(new Foothold(new Point(-200, 200), new Point(200, 200), 2));
                    BotMovementState state = state(map, -Integer.signum(target) * 29, speed);
                    for (int i = 0; i < 120; i++) {
                        approach(state, target, 4);
                        assertFalse(state.inAir, "fell: fs=" + slip + " speed=" + speed + " target=" + target);
                        assertEquals(130, state.bot.getPosition().y);
                    }
                    assertEquals(0.0, state.hspeed);
                    assertTrue(Math.abs(state.bot.getPosition().x - target) <= 4);
                }
            }
        }
    }

    @Test
    void changingDirectionDoesNotResetOrDiscardMomentum() {
        BotMovementState state = state(floor(1600, 1.0f), 0, 200);
        for (int i = 0; i < 10; i++) approach(state, 600, 1);
        double speed = state.hspeed;
        assertTrue(speed > 0.0);
        approach(state, 0, 1);
        assertNotEquals(0.0, state.hspeed, "retargeting must brake via physics, not zero velocity");
        for (int i = 0; i < 120; i++) approach(state, 0, 1);
        assertEquals(0.0, state.hspeed);
        assertTrue(Math.abs(state.bot.getPosition().x) <= 1);
    }

    @Test
    void aSameHeightDisconnectedTargetIsNotAGroundWaypoint() {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(100, 130), new Point(160, 130), 2));
        BotMovementState state = state(map, 0, 100);
        state.graphWarmupFallback = true;
        state.moveTarget = new Point(130, 130);
        state.moveTargetPrecise = true;
        for (int i = 0; i < 40; i++) BotMovementManager.tickGrounded(state, state.moveTarget);
        assertEquals(0, state.bot.getPosition().x);
        assertTrue(state.navAwaitingRoute);
        assertNull(state.navTargetPos);
    }

    @Test
    void oneConnectedUphillSurfaceIsNotMistakenForAnUnreachableUpperFloor() {
        MapleMap map = floor(200, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-300, -500), new Point(300, 600));
        Foothold slope = new Foothold(new Point(-100, 180), new Point(100, 80), 1);
        tree.insert(slope);
        map.setFootholds(tree);
        BotMovementState state = state(map, 0, 100);
        state.graphWarmupFallback = true;
        Point target = new Point(90, 85);
        for (int i = 0; i < 80; i++) BotMovementManager.tickGrounded(state, target);
        assertFalse(state.navAwaitingRoute);
        assertFalse(state.inAir);
        assertTrue(Math.abs(state.bot.getPosition().x - 90) <= 12);
    }

    @Test
    void aNoRouteWaitBrakesWithoutHuntingTheMovingStopPoint() {
        MapleMap map = floor(1600, 0.2f);
        BotMovementState state = state(map, 0, 200);
        for (int i = 0; i < 8; i++) approach(state, 600, 0);
        assertTrue(state.hspeed > 0);
        state.graphWarmupFallback = true;
        state.moveTarget = new Point(2, -200);
        double previous = state.hspeed;
        for (int i = 0; i < 80; i++) {
            BotMovementManager.tickGrounded(state, state.moveTarget);
            assertTrue(Math.abs(state.hspeed) <= Math.abs(previous));
            previous = state.hspeed;
        }
        assertEquals(0.0, state.hspeed);
        assertFalse(state.inAir);
        var cached = state.fallbackApproach;
        int stopped = state.bot.getPosition().x;
        for (int i = 0; i < 20; i++) BotMovementManager.tickGrounded(state, state.moveTarget);
        assertSame(cached, state.fallbackApproach, "waiting must not rescan every physics tick");
        assertEquals(stopped, state.bot.getPosition().x);
    }

    @Test
    void snowshoesUseTheSameFirmGroundControllerOnIce() {
        BotMovementState state = state(floor(1600, 0.2f), -120, 200);
        state.movementProfile = new BotMovementProfile(200, 100, true);
        for (int i = 0; i < 100; i++) approach(state, 2, 0);
        assertEquals(2, state.bot.getPosition().x);
        assertEquals(0.0, state.hspeed);
    }

    @Test
    void slipperyBrakeProjectionKeepsTheSignOfItsLastCounterSteerStep() throws Exception {
        MapleMap map = floor(1600, 0.2f);
        Foothold foothold = map.getFootholds().getAllFootholds().getFirst();
        BotMovementProfile profile = BotMovementProfile.base();
        var projection = BotPhysicsEngine.class.getDeclaredMethod("groundStoppingDisplacement",
                double.class, Foothold.class, double.class, double.class, double.class, boolean.class);
        projection.setAccessible(true);
        double force = profile.hForcePxs() * 0.008;
        double cap = force * BotPhysicsEngine.cfg.GROUNDSLIP
                / (BotPhysicsEngine.cfg.FRICTION + BotPhysicsEngine.cfg.SLOPEFACTOR);
        for (double speed : new double[]{0.012, -0.012, 0.03, -0.03, 0.2, -0.2, 2.0, -2.0}) {
            double predicted = (double) projection.invoke(null, speed, foothold, force, cap,
                    (double) map.getFootholdSpeed(), true);
            var state = new BotPhysicsEngine.GroundTravelState(0.0, speed, 0.0);
            Point position = new Point(0, 130);
            for (int i = 0; i < 40 && state.hspeed() != 0.0; i++) {
                var result = BotPhysicsEngine.simulateGroundMotion(map, position, foothold, 0,
                        state, profile, BotPhysicsEngine.GroundTarget.stop());
                state = result.state();
                position = result.point();
            }
            assertEquals(0.0, state.hspeed());
            assertEquals(state.physX(), predicted, 1e-9, "brake projection speed=" + speed);
        }
    }

    @Test
    void aRouteFailureCannotMoveTheBodyAfterItsCallbackStopsTheDriver() throws Exception {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
        BotMovementState state = state(map, -29, 100);
        state.moveTarget = new Point(2, 60);
        state.moveTargetPrecise = true;
        BotNavigationGraph graph = graph(map, false);
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
             var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(graph);
            movement.when(() -> GCMovement.abandonMove(state)).thenAnswer(inv -> {
                GCMovementDriver.stop(state);
                clearInvocations(state.bot);
                return null;
            });
            coreTick(state, true);
            assertTrue(state.tickStopped);
            verify(state.bot, never()).setPosition(any(Point.class));
        }
    }

    private static void driverTick(BotMovementState state) throws Exception {
        var method = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        method.setAccessible(true);
        method.invoke(null, state);
        MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
    }

    private static void prepareDriver(BotMovementState state) {
        state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        state.nextPlayerScanMs = Long.MAX_VALUE / 4;
        state.lastTickSpanMs = BotPhysicsEngine.cfg.TICK_MS;
        when(state.bot.getJob()).thenReturn(null);
    }

    @Test
    void fullDriverResumesTheSameMoveAfterALongGraphBuild() throws Exception {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
        BotMovementState state = state(map, -29, 100);
        prepareDriver(state);
        state.moveTarget = new Point(2, 60);
        state.moveTargetPrecise = true;
        BotNavigationGraph graph = graph(map, true);
        var build = new CompletableFuture<BotNavigationGraph>();
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
             var observers = mockStatic(ObserverTracker.class);
             var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            observers.when(() -> ObserverTracker.isActiveMap(map.getId())).thenReturn(true);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(null);
            provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(map, state.movementProfile)).thenReturn(build);
            for (int i = 0; i < 200; i++) {
                driverTick(state);
                assertNotNull(state.moveTarget, "a pending build is not a movement stall");
            }
            assertEquals(new Point(-29, 130), state.bot.getPosition());
            build.complete(graph);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(graph);
            for (int i = 0; i < 120 && state.moveTarget != null; i++) driverTick(state);
            assertNull(state.moveTarget);
            assertEquals(60, state.bot.getPosition().y);
            assertFalse(state.inAir);
            movement.verify(() -> GCMovement.abandonMove(state), never());
            movement.verify(() -> GCMovement.fireArrival(state), times(1));
        }
    }

    @Test
    void aFailedGraphBuildFailsOnceWithoutUpdatingTheBodyAfterTheCallback() throws Exception {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
        BotMovementState state = state(map, -29, 100);
        prepareDriver(state);
        state.moveTarget = new Point(2, 60);
        state.moveTargetPrecise = true;
        var build = new CompletableFuture<BotNavigationGraph>();
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
             var observers = mockStatic(ObserverTracker.class);
             var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            observers.when(() -> ObserverTracker.isActiveMap(map.getId())).thenReturn(true);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(null);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(null);
            provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(map, state.movementProfile)).thenReturn(build);
            driverTick(state);
            build.completeExceptionally(new IllegalStateException("graph build failed"));
            movement.when(() -> GCMovement.abandonMove(state)).thenAnswer(inv -> {
                GCMovementDriver.stop(state);
                clearInvocations(state.bot);
                return null;
            });
            driverTick(state);
            assertNull(state.moveTarget);
            assertTrue(state.tickStopped);
            movement.verify(() -> GCMovement.abandonMove(state), times(1));
            verify(state.bot, never()).setPosition(any(Point.class));
            verify(state.bot, never()).setStance(anyInt());
        }
    }

    @Test
    void aMovementTimeoutFinishesItsBodyUpdatesBeforeTheFailureCallback() throws Exception {
        MapleMap map = floor(1600, 1.0f);
        BotMovementState state = state(map, 0, 100);
        prepareDriver(state);
        state.moveTarget = new Point(200, 130);
        state.moveBestDist = 200;
        state.moveProgressAtMs = MovementClock.nowMs() - 9_000;
        try (var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            movement.when(() -> GCMovement.abandonMove(state)).thenAnswer(inv -> {
                GCMovementDriver.stop(state);
                clearInvocations(state.bot);
                return null;
            });
            driverTick(state);
            assertNull(state.moveTarget);
            assertTrue(state.tickStopped);
            movement.verify(() -> GCMovement.abandonMove(state), times(1));
            verify(state.bot, never()).setPosition(any(Point.class));
            verify(state.bot, never()).setStance(anyInt());
        }
    }

    @Test
    void aNoRouteSearchRescuesADeadPitBeforeTheFailureCallback() throws Exception {
        MapleMap map = floor(200, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(-100, -100), new Point(100, -100), 2));
        BotMovementState state = state(map, 0, 100);
        state.moveTarget = new Point(0, -100);
        state.moveTargetPrecise = true;
        BotNavigationGraph graph = graph(map, false);
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
             var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map)).thenReturn(graph);
            movement.when(() -> GCMovement.abandonMove(state)).thenAnswer(inv -> {
                assertEquals(new Point(0, -100), state.bot.getPosition(), "rescue precedes the callback");
                assertFalse(state.inAir);
                GCMovementDriver.stop(state);
                clearInvocations(state.bot);
                return null;
            });
            coreTick(state, true);
            assertEquals(new Point(0, -100), state.bot.getPosition());
            assertNull(state.moveTarget);
            movement.verify(() -> GCMovement.abandonMove(state), times(1));
            movement.verify(() -> GCMovement.fireArrival(state), never());
            verify(state.bot, never()).setPosition(any(Point.class));
        }
    }

    @Test
    void theFullDriverRescuesTheRealLpqStage3Pit() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(
                java.nio.file.Path.of("wz", "Map.wz")), "Map.wz needed");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010300);
        BotMovementState state = state(map, 0, 100);
        prepareDriver(state);
        BotPhysicsEngine.teleportTo(state, state.bot, new Point(0, 242));
        BotMovementManager.resetEntryStateAfterTeleport(state);
        BotNavigationGraphProvider.getGraph(map, state.movementProfile);
        state.moveTarget = new Point(-260, -160);
        state.moveTargetPrecise = true;
        try (var observers = mockStatic(ObserverTracker.class);
             var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            observers.when(() -> ObserverTracker.isActiveMap(map.getId())).thenReturn(true);
            for (int i = 0; i < 40 && state.moveTarget != null; i++) driverTick(state);
            assertNull(state.moveTarget);
            assertFalse(state.inAir);
            assertTrue(state.bot.getPosition().y < 242, "the no-path branch must not strand a bot in the basin");
            movement.verify(() -> GCMovement.abandonMove(state), times(1));
            movement.verify(() -> GCMovement.fireArrival(state), never());
        }
    }

    @Test
    void substepDeparturePreservesSpeedAndSpendsOnlyTheRemainingAirTime() {
        for (int dir : new int[]{-1, 1}) {
            for (double carry : new double[]{0.0, 2.0, 4.0, 6.0}) {
                MapleMap map = floor(60, 1.0f);
                map.getFootholds().insert(new Foothold(new Point(-500, 200), new Point(500, 200), 2));
                BotMovementState state = state(map, dir * 30, 200);
                state.hspeed = dir * 2.0;
                state.groundPhysicsCarryMs = carry;
                var target = new BotPhysicsEngine.GroundTarget(0, 4);
                var departure = BotPhysicsEngine.simulateGroundMotion(map, state.bot.getPosition(), state.groundFoothold,
                        -dir, new BotPhysicsEngine.GroundTravelState(state.physX, state.hspeed, carry),
                        state.movementProfile, target);
                assertTrue(departure.lostGround());
                assertEquals(42.0 + carry, departure.remainingAirMs());
                state.moveDir = -dir;
                BotPhysicsEngine.applyGroundMotion(state, state.bot, state.groundFoothold, target);
                assertTrue(state.inAir);
                int velocity = (int) Math.round(departure.state().hspeed() * BotPhysicsEngine.cfg.TICK_MS
                        / MapleMovement.CLIENT_STEP_MS);
                assertEquals(velocity, state.airVelX, "velocity cannot be the one-pixel departure displacement");
                double fraction = departure.remainingAirMs() / BotPhysicsEngine.cfg.TICK_MS;
                double steer = -dir * BotPhysicsEngine.cfg.AIR_CONTROL_ACCEL_PXSS
                        * Math.pow(BotPhysicsEngine.tickS(), 2) * fraction;
                assertEquals(departure.state().physX() + (velocity + steer) * fraction, state.physX, 1e-6);
                assertEquals(130 + 0.5 * BotPhysicsEngine.gravityPerTick() * fraction * fraction, state.physY, 1e-6);
                assertEquals(BotPhysicsEngine.gravityPerTick() * fraction, state.velY, 1e-5);
                assertEquals(0.0, state.groundPhysicsCarryMs);
            }
        }
    }

    @Test
    void aSubstepFallCanLandDuringTheRemainingPartOfTheTick() {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(34, 131), new Point(200, 131), 2));
        BotMovementState state = state(map, 30, 200);
        state.hspeed = 2.0;
        state.moveDir = -1;
        var result = BotPhysicsEngine.applyGroundMotion(state, state.bot, state.groundFoothold,
                new BotPhysicsEngine.GroundTarget(0, 4));
        assertFalse(state.inAir, "the unused air time must resolve collisions, not just extrapolate x");
        assertFalse(result.lostGround());
        assertEquals(131, state.bot.getPosition().y);
        assertEquals(2, state.groundFoothold.getId());
        assertTrue(state.hspeed > 0.0, "a partial-tick landing still carries momentum");
    }

    @Test
    void aClosestProfileJumpWaitsForTheExactGraphWithoutTimingOut() throws Exception {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
        BotMovementState state = state(map, -29, 125);
        prepareDriver(state);
        state.moveTarget = new Point(2, 60);
        state.moveTargetPrecise = true;
        BotNavigationGraph closest = graph(map, true);
        var build = new CompletableFuture<BotNavigationGraph>();
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
             var observers = mockStatic(ObserverTracker.class);
             var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            observers.when(() -> ObserverTracker.isActiveMap(map.getId())).thenReturn(true);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(closest);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(null);
            provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(map, state.movementProfile)).thenReturn(build);
            for (int i = 0; i < 200; i++) {
                driverTick(state);
                assertNotNull(state.moveTarget, "a closest-profile jump cannot run the movement stall clock");
                assertFalse(state.inAir, "the wrong profile's jump must not fire");
            }
            assertEquals("jump-graph-warmup", state.lastEdgeBlockReason);
            build.complete(closest);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(closest);
            for (int i = 0; i < 120 && state.moveTarget != null; i++) driverTick(state);
            assertNull(state.moveTarget);
            assertEquals(60, state.bot.getPosition().y);
            movement.verify(() -> GCMovement.abandonMove(state), never());
            movement.verify(() -> GCMovement.fireArrival(state), times(1));
        }
    }

    @Test
    void aClosestProfileBuildFailureEndsTheWaitingJumpBeforeItsCallback() throws Exception {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
        BotMovementState state = state(map, -29, 125);
        prepareDriver(state);
        state.moveTarget = new Point(2, 60);
        state.moveTargetPrecise = true;
        BotNavigationGraph closest = graph(map, true);
        var build = new CompletableFuture<BotNavigationGraph>();
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
             var observers = mockStatic(ObserverTracker.class);
             var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            observers.when(() -> ObserverTracker.isActiveMap(map.getId())).thenReturn(true);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(closest);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(null);
            provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(map, state.movementProfile)).thenReturn(build);
            for (int i = 0; i < 30; i++) driverTick(state);
            build.completeExceptionally(new IllegalStateException("exact graph failed"));
            movement.when(() -> GCMovement.abandonMove(state)).thenAnswer(inv -> {
                assertEquals(0.0, state.hspeed, "the moving approach must settle before handover");
                GCMovementDriver.stop(state);
                clearInvocations(state.bot);
                return null;
            });
            driverTick(state);
            assertNull(state.moveTarget);
            assertTrue(state.tickStopped);
            verify(state.bot, never()).setPosition(any(Point.class));
            verify(state.bot, never()).setStance(anyInt());
            movement.verify(() -> GCMovement.abandonMove(state), times(1));
        }
    }

    @Test
    void replacingTheGoalDoesNotConsumeThePreviousBuildFailure() throws Exception {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
        Character bot = state(map, -29, 100).bot;
        DriverlessMovementState.inject(bot);
        BotMovementState state = GCMovement.stateOf(bot);
        prepareDriver(state);
        var build = new CompletableFuture<BotNavigationGraph>();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        var arrivals = new java.util.concurrent.atomic.AtomicInteger();
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
             var observers = mockStatic(ObserverTracker.class)) {
            observers.when(() -> ObserverTracker.isActiveMap(map.getId())).thenReturn(true);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(null);
            provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(map, state.movementProfile)).thenReturn(build);
            GCMovement.move(bot, 2, 60);
            driverTick(state);
            assertTrue(state.navAwaitingRoute);
            build.completeExceptionally(new IllegalStateException("old build failed"));
            GCMovement.move(bot, 10, 130, arrivals::incrementAndGet, failures::incrementAndGet);
            for (int i = 0; i < 100 && state.moveTarget != null; i++) driverTick(state);
            assertNull(state.moveTarget);
            assertTrue(Math.abs(bot.getPosition().x - 10) <= 8);
            assertEquals(1, arrivals.get());
            assertEquals(0, failures.get(), "an obsolete wait cannot fail the new same-floor move");
        } finally {
            GCMovement.clearMoveIntent(bot);
            DriverlessMovementState.remove(bot.getId());
        }
    }

    @Test
    void aSwimDepartureUsesWaterUnitsAndTheRemainingTickImmediately() {
        for (boolean lowerFloor : new boolean[]{false, true}) {
            for (int dir : new int[]{-1, 1}) {
                for (double carry : new double[]{0.0, 2.0, 4.0, 6.0}) {
                    MapleMap map = floor(60, 1.0f);
                    map.setSwim(true);
                    if (lowerFloor) {
                        map.getFootholds().insert(new Foothold(new Point(-500, 200), new Point(500, 200), 2));
                    } else {
                        // Keep the column open inside the map's standable X bounds, not at a side wall.
                        map.getFootholds().insert(new Foothold(new Point(-500, 80), new Point(-450, 80), 3));
                        map.getFootholds().insert(new Foothold(new Point(450, 80), new Point(500, 80), 4));
                    }
                    BotMovementState state = state(map, dir * 30, 200);
                    state.hspeed = dir * 2.0;
                    state.groundPhysicsCarryMs = carry;
                    var target = new BotPhysicsEngine.GroundTarget(0, 4);
                    var departure = BotPhysicsEngine.simulateGroundMotion(map, state.bot.getPosition(), state.groundFoothold,
                            -dir, new BotPhysicsEngine.GroundTravelState(state.physX, state.hspeed, carry),
                            state.movementProfile, target);
                    assertTrue(departure.lostGround());
                    double elapsed = departure.remainingAirMs() / 1000.0;
                    var expected = MapleMovement.swimStep(departure.state().hspeed() / 0.008, 0.0, -dir, 0, elapsed);
                    state.moveDir = -dir;
                    BotPhysicsEngine.applyGroundMotion(state, state.bot, state.groundFoothold, target);
                    assertTrue(state.inAir);
                    assertTrue(state.swimming, "water takes ownership on the departure tick");
                    assertEquals(0, state.airVelX);
                    assertEquals(expected.vx(), state.hspeed, 1e-6);
                    assertEquals(expected.vy(), state.velY, 1e-5);
                    assertEquals(departure.state().physX() + expected.vx() * elapsed, state.physX, 1e-6);
                    assertEquals(130 + expected.vy() * elapsed, state.physY, 1e-6);
                    var next = MapleMovement.swimStep(expected.vx(), expected.vy(), -dir, 0, BotPhysicsEngine.tickS());
                    BotPhysicsEngine.applySwimMotion(state);
                    assertEquals(next.vx(), state.hspeed, 1e-6);
                    assertEquals(next.vy(), state.velY, 1e-4);
                }
            }
        }
    }

    @Test
    void theFullDriverEntersWaterOnTheDepartureTick() throws Exception {
        MapleMap map = floor(60, 1.0f);
        map.setSwim(true);
        map.getFootholds().insert(new Foothold(new Point(-500, 200), new Point(500, 200), 2));
        BotMovementState state = state(map, 30, 200);
        prepareDriver(state);
        state.hspeed = 2.0;
        state.moveTarget = new Point(0, 130);
        state.moveTargetPrecise = true;
        try (var observers = mockStatic(ObserverTracker.class)) {
            observers.when(() -> ObserverTracker.isActiveMap(map.getId())).thenReturn(true);
            driverTick(state);
            assertTrue(state.inAir);
            assertTrue(state.swimming);
            assertTrue(state.hspeed > 100.0, "swim velocity must be px/s, not zero or px/tick");
            assertEquals(0, state.airVelX);
            double beforeY = state.physY;
            driverTick(state);
            assertTrue(state.swimming);
            assertTrue(Math.abs(state.physY - beforeY) < 20.0, "no land-to-water unit spike");
        }
    }

    @Test
    void theWaterFloorClampsASubstepDepartureWithoutAnyFootholdBelow() {
        MapleMap map = floor(60, 1.0f);
        map.setSwim(true);
        map.setMapLineBoundings(-500, 131, -1000, 1000);
        map.getFootholds().insert(new Foothold(new Point(-500, 80), new Point(-450, 80), 3));
        map.getFootholds().insert(new Foothold(new Point(450, 80), new Point(500, 80), 4));
        BotMovementState state = state(map, 30, 200);
        state.hspeed = 2.0;
        state.moveDir = -1;
        BotPhysicsEngine.applyGroundMotion(state, state.bot, state.groundFoothold,
                new BotPhysicsEngine.GroundTarget(0, 4));
        assertTrue(state.swimming);
        assertEquals(131, state.bot.getPosition().y);
        assertEquals(0.0f, state.velY);
    }

    @Test
    void aRecoveredExactGraphOverridesThePreviousBuildFailure() throws Exception {
        for (boolean useClosestGraph : new boolean[]{false, true}) {
            MapleMap map = floor(60, 1.0f);
            map.getFootholds().insert(new Foothold(new Point(1, 60), new Point(3, 60), 2));
            BotMovementState state = state(map, -29, useClosestGraph ? 125 : 100);
            prepareDriver(state);
            state.moveTarget = new Point(2, 60);
            state.moveTargetPrecise = true;
            BotNavigationGraph graph = graph(map, true);
            var build = new CompletableFuture<BotNavigationGraph>();
            try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
                 var observers = mockStatic(ObserverTracker.class);
                 var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
                observers.when(() -> ObserverTracker.isActiveMap(map.getId())).thenReturn(true);
                provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile))
                        .thenReturn(useClosestGraph ? graph : null);
                provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(null);
                provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(map, state.movementProfile)).thenReturn(build);
                for (int i = 0; i < 30; i++) driverTick(state);
                assertNotNull(state.moveTarget);
                build.completeExceptionally(new IllegalStateException("obsolete build failed"));
                // A retry or manual rebuild publishes a usable graph before the next driver tick.
                provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(graph);
                provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile)).thenReturn(graph);
                for (int i = 0; i < 120 && state.moveTarget != null; i++) driverTick(state);
                assertNull(state.moveTarget);
                assertEquals(60, state.bot.getPosition().y);
                movement.verify(() -> GCMovement.abandonMove(state), never());
                movement.verify(() -> GCMovement.fireArrival(state), times(1));
            }
        }
    }

    private static MapleMap departureFloor(int dir, int landingNearX, int landingFarX) {
        MapleMap map = floor(60, 1.0f);
        map.getFootholds().insert(new Foothold(new Point(Math.min(dir * landingNearX, dir * landingFarX), 200),
                new Point(Math.max(dir * landingNearX, dir * landingFarX), 200), 2));
        map.getFootholds().insert(new Foothold(new Point(-500, 80), new Point(-450, 80), 3));
        map.getFootholds().insert(new Foothold(new Point(450, 80), new Point(500, 80), 4));
        return map;
    }

    @Test
    void aCounterSteeredDepartureCannotUseTheConstantSpeedLandingAsItsSafetyProof() throws Exception {
        for (int dir : new int[]{-1, 1}) {
            for (double carry : new double[]{0.0, 2.0, 4.0, 6.0}) {
                MapleMap map = departureFloor(dir, 80, 100);
                BotMovementState state = state(map, dir * 30, 200);
                prepareDriver(state);
                state.hspeed = dir * 2.0;
                state.groundPhysicsCarryMs = carry;
                state.moveTarget = new Point(0, 130);
                state.moveTargetPrecise = true;
                var departure = BotPhysicsEngine.simulateGroundMotion(map, state.bot.getPosition(), state.groundFoothold,
                        -dir, new BotPhysicsEngine.GroundTravelState(state.physX, state.hspeed, carry),
                        state.movementProfile, new BotPhysicsEngine.GroundTarget(0, 4));
                assertTrue(departure.lostGround());
                assertNotNull(BotPhysicsEngine.simulateFallLanding(map, departure.point(), departure.departureStepX()),
                        "the obsolete ballistic preflight would accept this fall");
                try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS);
                     var observers = mockStatic(ObserverTracker.class)) {
                    observers.when(() -> ObserverTracker.isActiveMap(map.getId())).thenReturn(true);
                    provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile)).thenReturn(null);
                    provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(map, state.movementProfile))
                            .thenReturn(new CompletableFuture<>());
                    driverTick(state);
                    assertFalse(state.inAir, "counter-steering misses the remote landing; keep the bot supported");
                    assertEquals(new Point(dir * 30, 130), state.bot.getPosition());
                    assertEquals(0.0, state.hspeed, "rejecting the step must not retain the outward impulse forever");
                    for (int i = 0; i < 100 && state.moveTarget != null; i++) {
                        driverTick(state);
                        assertFalse(state.inAir);
                    }
                    assertNull(state.moveTarget, "the inward goal must remain reachable after holding the lip");
                }
            }
        }
    }

    @Test
    void aSafeCounterSteeredDepartureStillLandsWithTheLiveAirController() {
        for (int dir : new int[]{-1, 1}) {
            for (double carry : new double[]{0.0, 2.0, 4.0, 6.0}) {
                MapleMap map = departureFloor(dir, 62, 78);
                BotMovementState state = state(map, dir * 30, 200);
                state.hspeed = dir * 2.0;
                state.groundPhysicsCarryMs = carry;
                state.moveTarget = new Point(0, 130);
                state.moveDir = -dir;
                BotPhysicsEngine.applyGroundMotion(state, state.bot, state.groundFoothold,
                        new BotPhysicsEngine.GroundTarget(0, 4));
                assertTrue(state.inAir, "a genuinely safe brake-to-landing trajectory must not be rejected");
                for (int i = 0; i < 40 && state.inAir; i++) BotMovementManager.tickAirborne(state, state.moveTarget);
                assertFalse(state.inAir);
                assertEquals(200, state.bot.getPosition().y);
                assertEquals(2, state.groundFoothold.getId());
            }
        }
    }

    @Test
    void departureForecastMatchesLiveFlightAcrossSteeringPoliciesAndCollisions() throws Exception {
        var forecast = BotPhysicsEngine.class.getDeclaredMethod("hasGroundDepartureLanding",
                BotMovementState.class, BotPhysicsEngine.GroundStepResult.class, int.class,
                BotPhysicsEngine.GroundTarget.class);
        var launch = BotPhysicsEngine.class.getDeclaredMethod("beginFall",
                BotMovementState.class, Character.class, Point.class, int.class);
        var airStep = BotPhysicsEngine.class.getDeclaredMethod("stepAirborne",
                BotMovementState.class, Character.class, double.class);
        forecast.setAccessible(true);
        launch.setAccessible(true);
        airStep.setAccessible(true);
        for (int dir : new int[]{-1, 1}) {
            for (int landingNear : new int[]{40, 62, 80}) {
                for (int input : new int[]{-dir, 0, dir}) {
                    for (boolean committed : new boolean[]{false, true}) {
                        MapleMap map = departureFloor(dir, landingNear, landingNear + 18);
                        // A collidable side wall exercises velocity reset in both implementations.
                        Foothold wall = new Foothold(new Point(dir * 85, 400), new Point(dir * 85, 160), 5);
                        map.getFootholds().insert(wall);
                        BotMovementState state = state(map, dir * 30, 200);
                        state.moveTarget = new Point(0, 130);
                        if (committed) {
                            state.navEdge = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.DROP,
                                    new Point(dir * 30, 130), new Point(dir * landingNear, 200), dir * 9, 0, 0, 0, 0, 500);
                        }
                        var departure = new BotPhysicsEngine.GroundStepResult(new Point(dir * 31, 130),
                                state.groundFoothold, new BotPhysicsEngine.GroundTravelState(dir * 31.4, dir * 1.5, 2.0),
                                dir, 30 * dir, true, input, 42.0);
                        var target = new BotPhysicsEngine.GroundTarget(0, 4);
                        boolean predicted = (boolean) forecast.invoke(null, state, departure, input, target);
                        launch.invoke(null, state, state.bot, departure.point(), departure.departureStepX());
                        state.physX = departure.state().physX();
                        state.moveDir = input;
                        airStep.invoke(null, state, state.bot, departure.remainingAirMs() / BotPhysicsEngine.cfg.TICK_MS);
                        state.moveDir = 0;
                        for (int i = 0; i < 100 && state.inAir && state.physY <= 1200; i++) {
                            BotMovementManager.tickAirborne(state, state.moveTarget);
                        }
                        assertEquals(!state.inAir, predicted, "dir=" + dir + " landing=" + landingNear
                                + " input=" + input + " committed=" + committed);
                    }
                }
            }
        }
    }

    @Test
    void connectedSwitchbackStillNeedsALocalCrossingInsteadOfWalkingAtTheUpperX() {
        MapleMap map = floor(200, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-300, -500), new Point(300, 600));
        Foothold lower = new Foothold(new Point(-100, 130), new Point(0, 130), 1);
        Foothold ramp = new Foothold(new Point(0, 130), new Point(100, 80), 2);
        Foothold upper = new Foothold(new Point(-100, 80), new Point(100, 80), 3);
        lower.setNext(2);
        ramp.setPrev(1);
        ramp.setNext(3);
        upper.setNext(2);
        tree.insert(lower);
        tree.insert(ramp);
        tree.insert(upper);
        map.setFootholds(tree);
        BotMovementState state = state(map, -80, 100);
        state.graphWarmupFallback = true;
        Point target = new Point(-80, 80);
        assertTrue(BotFallbackMovementManager.tryImmediateAction(state, state.bot.getPosition(), target),
                "the upper x overlaps our feet but it needs the reachable vertical jump, not a zero-length walk");
        assertTrue(state.inAir);
    }
}
