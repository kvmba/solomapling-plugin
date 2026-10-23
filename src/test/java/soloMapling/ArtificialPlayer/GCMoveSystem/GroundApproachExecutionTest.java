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
            provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(map, state.movementProfile)).thenAnswer(inv -> null);
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
