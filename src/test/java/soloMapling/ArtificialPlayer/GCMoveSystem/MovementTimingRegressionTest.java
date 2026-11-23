package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MovementTimingRegressionTest {
    private static final int KERNING = 103000800;
    private static final int TICK_MS = BotPhysicsEngine.cfg.TICK_MS;
    private static Method tick;

    @BeforeAll
    static void boot() throws Exception {
        PqParkDoorExecutionSimTest.stubHostEnvironment();
        tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
    }

    @BeforeEach
    void useClock() {
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterEach
    void clearObserver() {
        ObserverTracker.observe(Set.of(), Set.of(), MovementClock.nowMs() + 10_000L);
        MovementClock.reset();
    }

    @Test
    void aColdCrossPlatformPlanWalksToTheFirstLaunchInsteadOfTeleporting() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(KERNING);
        var graph = BotNavigationGraphProvider.getGraph(map);
        Point start = ground(map, -690, 130);
        Point goal = new Point(-315, 102);
        int from = graph.findRegionId(map, start);
        int to = graph.findRegionId(map, goal);
        assertNotEquals(from, to);
        var route = BotNavigationManager.findPath(graph, map, start, from, to, goal, false);
        assertNotNull(route);
        assertTrue(start.distance(route.getFirst().startPoint) > 200);

        var state = moving(map, start, goal);
        frame(state, TICK_MS);
        assertTrue(state.coarseActive, "the gently sloped approach should plan an analytic walk");
        assertEquals(start, state.bot.getPosition(), "starting an unobserved route must not teleport to the first jump");
        for (int i = 1; i < state.coarsePlan.edges.size(); i++) {
            assertTrue(state.coarsePlan.edges.get(i - 1).endPoint.distance(state.coarsePlan.edges.get(i).startPoint) <= 1,
                    "every baked edge and its approach must join without a positional gap");
        }
        frame(state, 800);
        Point before = state.bot.getPosition();
        assertTrue(before.x > start.x && before.x < route.getFirst().startPoint.x);
        occupied(map);
        frame(state, TICK_MS);
        assertFalse(state.coarseActive);
        assertTrue(before.distance(state.bot.getPosition()) <= 10, "the first full tick must continue from the visible position");
        assertEquals(goal, state.moveTarget);
    }

    @Test
    void observationAndDwellAllowAnInProgressWalkToSwitchBothWays() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(910000000);
        BotNavigationGraphProvider.getGraph(map);
        Point start = new Point(-690, 34);
        Point goal = new Point(1200, 34);
        var state = moving(map, start, goal);
        frame(state, 650);
        frame(state, TICK_MS);
        assertTrue(state.coarseActive);
        Point before = state.bot.getPosition();
        occupied(map);
        frame(state, TICK_MS);
        assertFalse(state.coarseActive);
        assertTrue(before.distance(state.bot.getPosition()) <= 10);

        ObserverTracker.observe(Set.of(), Set.of(), MovementClock.nowMs());
        for (int i = 0; i < 40; i++) frame(state, TICK_MS);
        assertTrue(ObserverTracker.isActiveMap(map));
        assertFalse(state.coarseActive, "a player leaving must not immediately downgrade the physics");

        for (int i = 0; i < 45; i++) frame(state, TICK_MS);
        ObserverTracker.observe(Set.of(), Set.of(), MovementClock.nowMs());
        assertFalse(ObserverTracker.isActiveMap(map));
        before = state.bot.getPosition();
        frame(state, TICK_MS);
        assertTrue(state.coarseActive);
        assertTrue(before.distance(state.bot.getPosition()) <= 2, "demotion must not restart the walk at another platform");
        assertEquals(goal, state.moveTarget);
        for (int i = 0; i < 24 && state.moveTarget != null; i++) frame(state, 1000);
        assertNull(state.moveTarget);
        assertTrue(GCMovement.withinArrivalBox(state.bot.getPosition(), goal));
    }

    @Test
    void promotionAtACoordinateSharedByTwoPlatformsKeepsTheOriginalFloor() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(103040200);
        var graph = BotNavigationGraphProvider.getGraph(map);
        Point start = new Point(-837, 89);
        Point seam = new Point(-843, 89);
        var state = moving(map, start, new Point(-863, 89));
        assertNotEquals(graph.findRegionId(map, start), graph.findRegionId(map, seam));
        frame(state, (int) Math.round(6000 / state.movementProfile.walkVelocityPxs()));
        frame(state, TICK_MS);
        assertTrue(state.coarseActive, "the analytic walk must keep the FH34 line beside the crossing slope");
        assertEquals(seam, state.bot.getPosition());
        occupied(map);
        frame(state, TICK_MS);
        assertFalse(state.coarseActive);
        assertFalse(state.inAir);
        assertEquals(34, state.groundContact.getId());
    }

    @Test
    void aPlayerArrivingDoesNotTurnAnAnalyticRopePositionIntoFloatingGround() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(KERNING);
        var graph = BotNavigationGraphProvider.getGraph(map);
        var exit = graph.regions.stream().filter(r -> r.isRopeRegion)
                .flatMap(r -> graph.getOutgoing(r.id).stream())
                .filter(e -> e.type == BotNavigationGraph.EdgeType.CLIMB && !graph.getRegion(e.toRegionId).isRopeRegion
                        && e.launchStepX != 0 && e.cost > 100 && e.endPoint.distance(e.startPoint) > 25)
                .filter(e -> {
                    Point floor = BotPhysicsEngine.findGroundPoint(map, e.startPoint);
                    return floor == null || floor.y - e.startPoint.y > 80;
                }).findFirst().orElseThrow();
        var state = moving(map, exit.startPoint, exit.endPoint);
        state.coarsePlan = MovementPlan.inMap(map.getId(), List.of(exit));
        state.coarsePlanTarget = exit.endPoint;
        state.coarsePlanMapId = map.getId();
        state.coarsePlanStartMs = MovementClock.nowMs() - exit.cost / 2;
        frame(state, TICK_MS);
        assertTrue(state.coarseActive);
        occupied(map);
        frame(state, TICK_MS);
        assertFalse(state.coarseActive);
        assertTrue(state.climbing || state.inAir && state.navFlightEdge != null,
                "promotion must restore the rope or execute an actual rope exit, not free-fall");
        assertTrue(exit.startPoint.distance(state.bot.getPosition()) <= 12, "promotion must not snap to a floor below");
    }

    @ParameterizedTest
    @CsvSource({"1, 1, 2", "1, -1, 4", "-1, 1, 6"})
    void anAirborneSecondHitStillLandsSafely(int firstDir, int secondDir, int betweenHits) throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010300);
        var graph = BotNavigationGraphProvider.getGraph(map);
        Point goal = new Point(25, -1247);
        var state = moving(map, new Point(190, -1247), goal);
        occupied(map);
        frame(state, TICK_MS);
        assertNotEquals(190, state.bot.getPosition().x, "take the first hit during a real walk");
        BotContactDamage.applyKnockbackMotion(state, firstDir * 9);
        assertTrue(state.inAir);
        for (int i = 0; i < betweenHits; i++) frame(state, TICK_MS);
        assertTrue(state.inAir, "the second hit must occur before the first landing");
        var before = state.bot.getPosition();
        float vy = state.velY;
        BotContactDamage.applyKnockbackMotion(state, secondDir * 9);
        assertEquals(secondDir * 9, state.airVelX);
        assertEquals(vy, state.velY, "an air hit must not restart the vertical jump");
        var landing = BotPhysicsEngine.simulateLanding(map, before, vy, state.airVelX, state.downJumpGracePeriodMS);
        assertNotNull(landing);
        for (int i = 0; i < 60 && state.inAir; i++) frame(state, TICK_MS);
        assertFalse(state.inAir);
        assertEquals(landing.point(), state.bot.getPosition());
        assertEquals(graph.regionIdByFootholdId.get(landing.foothold().getId()),
                BotNavigationManager.resolveCurrentRegionId(graph, state, map, state.bot.getPosition()));
        assertTrue(DeadPitGuard.isLivableSurface(map, state.groundContact, state.movementProfile));
    }

    @Test
    void observationDoesNotResetAnAlreadyLaunchedHit() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(KERNING);
        BotNavigationGraphProvider.getGraph(map);
        Point goal = ground(map, 630, 110);
        var state = moving(map, ground(map, 340, 102), goal);
        frame(state, 200);
        frame(state, TICK_MS);
        assertTrue(state.coarseActive);
        occupied(map);
        BotContactDamage.applyKnockbackMotion(state, -9);
        assertTrue(state.inAir, "a hit can launch while the old coarse plan is still stored");
        Point before = state.bot.getPosition();
        frame(state, TICK_MS);
        assertFalse(state.coarseActive);
        assertTrue(state.inAir);
        assertTrue(state.bot.getPosition().y < before.y, "promotion must keep the recoil's upward impulse");
        assertEquals(goal, state.moveTarget);
    }

    private static BotMovementState moving(MapleMap map, Point start, Point target) {
        var bot = new CharacterDouble(931780, map, null, null);
        var state = new BotMovementState(bot, null);
        state.lastMap = map;
        state.lastProfileRefreshMs = Long.MAX_VALUE;
        BotPhysicsEngine.teleportTo(state, bot, start);
        state.moveTarget = target;
        state.moveTargetMap = map;
        state.moveTargetPrecise = true;
        state.moveProgressAtMs = MovementClock.nowMs();
        state.moveBestDist = Integer.MAX_VALUE;
        return state;
    }

    private static Point ground(MapleMap map, int x, int y) {
        return BotPhysicsEngine.findGroundPoint(map, new Point(x, y));
    }

    private static void occupied(MapleMap map) {
        ObserverTracker.observe(Set.of(map), Set.of(), MovementClock.nowMs());
    }

    private static void frame(BotMovementState state, int elapsed) throws Exception {
        tick.invoke(null, state);
        state.lastTickSpanMs = elapsed;
        MovementClock.advance(elapsed);
    }
}
