package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;

class MovementSafetyRegressionTest {
    @BeforeAll
    static void boot() throws Exception {
        PqParkDoorExecutionSimTest.stubHostEnvironment();
    }

    @AfterAll
    static void resetClock() {
        MovementClock.reset();
    }

    @Test
    void aTouchingUnlinkedSlopeDoesNotStealGroundContact() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(103040200);
        var graph = BotNavigationGraphProvider.getGraph(map);
        Point goal = new Point(-854, 89);
        var state = at(map, new Point(-837, 89), graph.movementProfile);
        state.moveTarget = goal;
        state.moveTargetMap = map;
        state.moveTargetPrecise = true;
        state.navPreciseTarget = true;
        for (int i = 0; i < 20 && state.moveTarget != null; i++) {
            BotMovementManager.tickGrounded(state, goal);
            assertFalse(state.inAir, "touching FH3 must not switch the bot off the FH34 ledge");
            assertEquals(34, BotPhysicsEngine.standingFoothold(state, state.bot.getPosition()).getId());
            GCMovementDriver.clearReachedMoveTarget(state);
        }
        assertNull(state.moveTarget);
        assertTrue(GCMovement.withinArrivalBox(state.bot.getPosition(), goal));
    }

    @Test
    void aWalkOffWithNoLandingAtTheActualSpeedIsRefused() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        var graph = BotNavigationGraphProvider.getGraph(map);
        Point lip = new Point(173, -3180);
        var state = at(map, lip, graph.movementProfile);
        var contact = BotPhysicsEngine.findGroundFoothold(map, lip);
        assertEquals(390, contact.getId());
        state.moveDir = 1;
        state.hspeed = 1;
        var stride = BotPhysicsEngine.simulateLiveGroundMotion(state, lip, contact, 1);
        assertTrue(stride.lostGround());
        assertNotNull(BotPhysicsEngine.simulateFallLanding(map, stride.point(), 1), "old unit-speed probe gave a false yes");
        assertNull(BotPhysicsEngine.simulateFallLanding(map, stride.point(), stride.stepX()));
        assertFalse(BotPhysicsEngine.applyGroundMotion(state, state.bot, contact).lostGround());
        assertEquals(lip, state.bot.getPosition());
        assertEquals(0.0, state.hspeed);
        assertFalse(state.inAir);
    }

    @Test
    void contactKnockbackUsesItsOwnImpulseAndDoesNotFallIntoThePit() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010300);
        var profile = BotMovementProfile.base();
        BotNavigationGraphProvider.getGraph(map, profile);
        Point lip = new Point(-188, -943);
        var state = at(map, lip, profile);
        var oldProbe = BotPhysicsEngine.simulateJumpLanding(map, lip, -9, profile);
        assertTrue(oldProbe == null || DeadPitGuard.isLivableSurface(map, oldProbe.foothold(), profile));
        var recoil = BotPhysicsEngine.simulateLanding(map, lip, -21.875f, -9, 0);
        assertTrue(recoil == null || !DeadPitGuard.isLivableSurface(map, recoil.foothold(), profile));
        BotContactDamage.applyKnockbackMotion(state, -9);
        assertEquals(0, state.airVelX);
        for (int i = 0; i < 256 && state.inAir; i++) BotMovementManager.tickAirborne(state, lip);
        assertFalse(state.inAir);
        assertEquals(lip, state.bot.getPosition());
    }

    @ParameterizedTest
    @ValueSource(ints = {20001, 922010800})
    void aWalkOffRunwayCannotStartOnAnOverlappingDifferentPlatform(int mapId) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        var graph = BotNavigationGraphProvider.getGraph(map);
        int edges = 0;
        for (var region : graph.regions) {
            for (var edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX == 0) continue;
                edges++;
                assertEquals(edge.fromRegionId, graph.findRegionId(map, edge.startPoint),
                        () -> "map " + mapId + " has a walk-off starting on a different platform at " + edge.startPoint);
            }
        }
        assertTrue(edges > 0, "the valid walk-offs must remain");
    }

    @ParameterizedTest
    @ValueSource(ints = {222020111, 222020211})
    void theFallWatchdogDoesNotCancelASafeFallPastTheCameraBound(int mapId) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        Point start = new Point(0, 705);
        Point floor = new Point(0, 782);
        var state = at(map, start, BotMovementProfile.base());
        BotPhysicsEngine.beginPortalDrop(state, state.bot, start);
        Point goal = new Point(100, 782);
        state.moveTarget = goal;
        for (int i = 0; i < 20 && state.inAir; i++) {
            BotMovementManager.tickAirborne(state, start);
            GCMovementDriver.tickFallOffMapRecovery(state);
            assertEquals(goal, state.moveTarget, "recovery must not cancel the move while a real floor is below");
        }
        assertFalse(state.inAir);
        assertEquals(floor, state.bot.getPosition());
    }

    @Test
    void aDropOrJumpIntoTheGuildQuestPitIsNotAnOrdinaryRoute() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(990000100);
        var graph = BotNavigationGraphProvider.getGraph(map);
        int source = graph.regionIdByFootholdId.get(138);
        int pit = graph.regionIdByFootholdId.get(23);
        var intoPit = graph.getOutgoing(source).stream().filter(e -> e.toRegionId == pit).toList();
        assertTrue(intoPit.stream().anyMatch(e -> e.type == BotNavigationGraph.EdgeType.DROP));
        assertTrue(intoPit.stream().anyMatch(e -> e.type == BotNavigationGraph.EdgeType.JUMP));
        for (var edge : intoPit) {
            assertFalse(BotNavigationManager.isEdgeUsable(graph, map, edge, BotMovementCapabilities.NONE));
        }
        var edge = intoPit.getFirst();
        var state = at(map, edge.startPoint, graph.movementProfile);
        assertFalse(GCMovement.canPathTo(state.bot, edge.endPoint.x, edge.endPoint.y));
        assertNull(MovementPlan.inMap(graph, map, edge.startPoint, edge.endPoint, BotMovementCapabilities.NONE));
        assertNull(GCMovement.nearestReachableGround(state.bot,
                new java.awt.Rectangle(edge.endPoint.x - 16, edge.endPoint.y - 2, 33, 5)));
    }

    @ParameterizedTest
    @ValueSource(ints = {610030521, 610030522, 610030540})
    void retryPortalIsAnEscapeEvenWhenItsArtworkIsHighAboveTheFloor(int mapId) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        var graph = BotNavigationGraphProvider.getGraph(map);
        var edge = graph.regions.stream().flatMap(r -> graph.getOutgoing(r.id).stream())
                .filter(e -> e.type == BotNavigationGraph.EdgeType.PORTAL && e.startPoint.y - map.getPortal(e.portalId).getPosition().y > 80)
                .findFirst().orElseThrow();
        var contact = BotPhysicsEngine.findGroundFoothold(map, edge.startPoint);
        assertTrue(DeadPitGuard.isLivableSurface(map, contact, graph.movementProfile));
        var path = BotNavigationManager.findPath(graph, map, edge.startPoint, edge.fromRegionId, edge.toRegionId, edge.endPoint, false);
        assertNotNull(path);
        assertEquals(BotNavigationGraph.EdgeType.PORTAL, path.getFirst().type);
    }

    @Test
    void rescueHopUsesTheBotsActualJumpForce() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100040003);
        var profile = new BotMovementProfile(140, 123);
        var graph = BotNavigationGraphProvider.getGraph(map, profile);
        Point lip = new Point(-220, -2175);
        var state = at(map, lip, profile);
        int stride = BotPhysicsEngine.walkStep(map, profile);
        assertNotNull(BotPhysicsEngine.simulateJumpLanding(map, lip, -stride, BotMovementProfile.base()));
        assertNull(BotPhysicsEngine.simulateJumpLanding(map, lip, -stride, profile));
        BotMovementManager.tickUnstuck(state);
        if (!state.inAir) return; // without a safe alternative, holding on the FH is correct
        assertTrue(state.airVelX > 0, "the unsafe outward jump must never be chosen");
        var landing = BotPhysicsEngine.simulateJumpLanding(map, lip, state.airVelX, profile);
        assertNotNull(landing);
        for (int i = 0; i < 256 && state.inAir; i++) {
            BotMovementManager.tickAirborne(state, lip);
        }
        assertFalse(state.inAir);
        assertEquals(landing.point(), state.bot.getPosition());
        assertEquals(graph.regionIdByFootholdId.get(landing.foothold().getId()),
                BotNavigationManager.resolveCurrentRegionId(graph, state, map, state.bot.getPosition()));
    }

    private static BotMovementState at(MapleMap map, Point point, BotMovementProfile profile) {
        var bot = new CharacterDouble(1, map, null, null);
        var state = new BotMovementState(bot, null);
        state.movementProfile = profile;
        BotPhysicsEngine.teleportTo(state, bot, point);
        return state;
    }
}
