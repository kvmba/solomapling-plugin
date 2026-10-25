package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Rope;
import org.junit.BeforeClass;
import org.junit.Test;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.GCMoveSystem.NavigationReachabilityTest.*;

public class MovementAuditRegressionTest {
    @BeforeClass
    public static void initialize() throws Exception {
        initializeCharacterServices();
    }

    private static Portal exitAt(Point at, int mapId, String script) {
        Portal portal = mock(Portal.class);
        when(portal.getPosition()).thenReturn(at);
        when(portal.getTargetMapId()).thenReturn(mapId);
        when(portal.getScriptName()).thenReturn(script);
        return portal;
    }

    @Test
    public void aClosedCycleInABasinHasNoExitEvenWithMonsterSpawns() throws Exception {
        Foothold top = floor(1, -1000, -900, -300);
        Foothold left = floor(2, -100, 0, 100);
        Foothold right = floor(3, 10, 110, 100);
        MapleMap map = spy(geometry(top, left, right));
        Portal exit = exitAt(new Point(-950, -300), 100000000, null);
        when(map.getPortals()).thenReturn(List.of(exit));
        when(map.getMonsterSpawnPositions()).thenReturn(List.of(new Point(-40, 99)));
        assertNotNull(BotPhysicsEngine.simulateJumpLanding(map, new Point(-40, 100), 6));
        BotNavigationGraph graph = build(map);
        assertTrue(graph.deadRegionIds.contains(region(graph, left)));
        assertTrue(graph.deadRegionIds.contains(region(graph, right)));
        assertFalse(graph.deadRegionIds.contains(region(graph, top)));
    }

    @Test
    public void monsterSinkIsNotALivableLandingOrAnInboundRoute() throws Exception {
        Foothold top = floor(1, -100, 100, 0), pit = floor(2, -100, 100, 500);
        MapleMap map = spy(geometry(top, pit));
        Portal exit = exitAt(new Point(0, 0), 100000000, null);
        when(map.getPortals()).thenReturn(List.of(exit));
        when(map.getMonsterSpawnPositions()).thenReturn(List.of(new Point(0, 499)));
        BotNavigationGraph graph = build(map);
        assertTrue(graph.legalDestinationRegionIds.contains(region(graph, pit)));
        assertTrue(graph.deadRegionIds.contains(region(graph, pit)));
        assertNull(BotNavigationManager.findPath(graph, map, new Point(0, 0), region(graph, top),
                region(graph, pit), new Point(0, 500)));
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, graph.movementProfile)).thenReturn(graph);
            assertFalse(DeadPitGuard.isLivableLanding(map, new Point(0, 500), graph.movementProfile));
        }
    }

    @Test
    public void scriptedExitCountsButPlainSpawnSentinelDoesNot() throws Exception {
        Foothold top = floor(1, -100, 100, 0), lower = floor(2, -100, 100, 400);
        MapleMap map = spy(geometry(top, lower));
        Portal exit = exitAt(new Point(0, 400), 999999999, "party6_stage");
        when(map.getPortals()).thenReturn(List.of(exit));
        assertFalse(build(map).deadRegionIds.contains(2));
        when(exit.getScriptName()).thenReturn(null);
        assertTrue(build(map).deadRegionIds.contains(2));
    }

    @Test
    public void warmupCannotCallAMultiPlatformBasinSafe() {
        MapleMap map = geometry(floor(1, -100, 100, 0));
        try (var provider = mockStatic(BotNavigationGraphProvider.class)) {
            assertFalse(DeadPitGuard.isLivableLanding(map, new Point(0, 0), BotMovementProfile.base()));
            provider.verify(() -> BotNavigationGraphProvider.warmGraphAsync(map, BotMovementProfile.base()));
        }
    }

    @Test
    public void downBlinkChoosesTheDeepestSafeFloorNotTheRejectedBottom() throws Exception {
        MapleMap map = geometry(floor(1, -200, 200, 0), floor(2, -200, 200, 60), floor(3, -200, 200, 120));
        Method method = GCMovementSkills.class.getDeclaredMethod("downLanding", MapleMap.class, Point.class,
                BotMovementProfile.class, BotMovementCapabilities.class);
        method.setAccessible(true);
        try (var guard = mockStatic(DeadPitGuard.class)) {
            guard.when(() -> DeadPitGuard.isLivableLanding(eq(map), any(Point.class), any(), any()))
                    .thenAnswer(call -> ((Point) call.getArgument(1)).y == 60);
            assertEquals(new Point(0, 120), GCMovementSkills.downTeleportLanding(map, new Point(0, 0)));
            assertEquals(new Point(0, 60), method.invoke(null, map, new Point(0, 0),
                    BotMovementProfile.base(), BotMovementCapabilities.NONE));
        }
    }

    @Test
    public void flashJumpFitsTheCurrentProfileAndBraking() throws Exception {
        MapleMap map = geometry(floor(1, -500, 380, 0), floor(2, -2000, 2000, 500));
        BotNavigationGraph graph = build(map);
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map)).thenReturn(graph);
            for (int speed : new int[]{100, 120, 140, 160, 180, 200}) {
                BotMovementProfile profile = new BotMovementProfile(speed, 123);
                float scale = GCMovementSkills.fitScale(map, new Point(0, 0), 1, 1f, profile);
                assertTrue("a shorter learned tier fits", scale > 0);
                var stopped = BotPhysicsEngine.simulateLandingStop(map,
                        BotPhysicsEngine.simulateFlashJumpLanding(map, new Point(0, 0), 1, profile, scale), profile);
                assertFalse(stopped.lostGround());
                assertEquals(0, stopped.finalPoint().y);
                if (speed >= 160) assertTrue(scale < 1f);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, BotMovementState> states() throws Exception {
        Field field = GCMovement.class.getDeclaredField("STATES");
        field.setAccessible(true);
        return (Map<Integer, BotMovementState>) field.get(null);
    }

    @Test
    public void chaseTokenSurvivesIgnoredPixelShiftButCannotCancelReplacement() throws Exception {
        MapleMap map = geometry(floor(1, -100, 200, 0));
        Character bot = character(map, new Point(0, 0));
        when(bot.getId()).thenReturn(991001);
        BotMovementState state = new BotMovementState(bot, null);
        states().put(bot.getId(), state);
        try (var observer = mockStatic(ObserverTracker.class)) {
            Object first = GCMovement.moveIfTargetChanged(bot, 100, 0, 16);
            assertSame(first, GCMovement.moveIfTargetChanged(bot, 105, 0, 16));
            assertTrue(GCMovement.stopIfTarget(bot, first));
            Object old = GCMovement.moveIfTargetChanged(bot, 100, 0, 16);
            GCMovement.move(bot, 100, 0); // pickup owns the same pixel, but a different move
            assertFalse(GCMovement.stopIfTarget(bot, old));
            assertNotNull(state.moveTarget);
        } finally {
            states().remove(bot.getId());
        }
    }

    @Test
    public void retiringChaseDismountsRatherThanLatchingARopeHold() throws Exception {
        MapleMap map = geometry(floor(1, -100, 100, 100));
        Character bot = character(map, new Point(0, 0));
        when(bot.getId()).thenReturn(991002);
        BotMovementState state = new BotMovementState(bot, null);
        state.moveTarget = new Point(0, -100);
        state.moveTargetMap = map;
        state.climbing = true;
        state.climbRope = new Rope(0, -100, 100, true);
        states().put(bot.getId(), state);
        try {
            assertTrue(GCMovement.stopIfTarget(bot, state.moveTarget));
            assertFalse(state.climbPositionHeld);
            assertFalse(state.climbing);
            assertTrue(state.inAir);
        } finally {
            states().remove(bot.getId());
        }
    }

    @Test
    public void walkArrivalRejectsAirAndClimbButAcceptsIntentionalRopeSpot() throws Exception {
        MapleMap map = geometry(floor(1, -100, 100, 200));
        Character bot = character(map, new Point(0, 0));
        when(bot.getId()).thenReturn(991003);
        BotMovementState state = new BotMovementState(bot, null);
        states().put(bot.getId(), state);
        try {
            state.inAir = true;
            assertFalse(GCMovement.isAtTarget(bot, new Point(0, 0), 8));
            state.inAir = false;
            state.climbing = true;
            state.climbRope = new Rope(0, -100, 100, true);
            assertFalse(GCMovement.isAtTarget(bot, new Point(20, 0), 8));
            MapleMap noGround = mock(MapleMap.class);
            when(bot.getMap()).thenReturn(noGround);
            assertTrue(GCMovement.isAtTarget(bot, new Point(0, 0), 8));
        } finally {
            states().remove(bot.getId());
        }
    }

    @Test
    public void explicitRopeStopStillHoldsWhileAutomaticChaseCancellationDoesNot() throws Exception {
        MapleMap map = geometry(floor(1, -100, 100, 100));
        Character bot = character(map, new Point(0, 0));
        when(bot.getId()).thenReturn(991004);
        BotMovementState state = new BotMovementState(bot, null);
        state.climbing = true;
        state.climbRope = new Rope(0, -100, 100, true);
        states().put(bot.getId(), state);
        try {
            GCMovement.stop(bot);
            assertTrue(state.climbPositionHeld);
            assertTrue(state.climbing);
            assertFalse(state.inAir);
        } finally {
            states().remove(bot.getId());
        }
    }

    @Test
    public void aNegativeChaseMemoIsRecheckedAfterGraphRecovery() throws Exception {
        MapleMap map = geometry(floor(1, -100, 100, 0));
        Character bot = character(map, new Point(0, 0));
        Class<?> type = Class.forName(PqActions.class.getName() + "$SeekState");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();
        Method method = PqActions.class.getDeclaredMethod("pathableMemo", Character.class, int.class, int.class, type);
        method.setAccessible(true);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.peekRegionIdAt(map, 50, 0)).thenReturn(7);
            movement.when(() -> GCMovement.groundPointBelow(map, 50, 0)).thenReturn(new Point(50, 0));
            movement.when(() -> GCMovement.canPathTo(bot, 50, 0)).thenReturn(false, true);
            assertEquals(false, method.invoke(null, bot, 50, 0, state));
            assertEquals(true, method.invoke(null, bot, 50, 0, state));
        }
    }

    @Test
    public void snowshoeRebuildUsesTheSameKeyAsRuntimeOnDryGround() throws Exception {
        MapleMap map = geometry(floor(1, -100, 100, 0));
        var oldCache = BotNavigationGraphProvider.cacheDir();
        var directory = Files.createTempDirectory("nav-audit-");
        BotNavigationGraphProvider.setCacheDir(directory);
        try {
            var previous = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
            var shoes = new BotMovementProfile(100, 100, true);
            var rebuilt = BotNavigationGraphProvider.rebuildGraph(map, shoes);
            assertNotSame(previous, rebuilt);
            assertSame(rebuilt, BotNavigationGraphProvider.peekGraph(map, shoes));
        } finally {
            BotNavigationGraphProvider.setCacheDir(oldCache);
            try (var files = Files.list(directory)) {
                for (var path : files.toList()) Files.delete(path);
            }
            Files.delete(directory);
        }
    }
}
