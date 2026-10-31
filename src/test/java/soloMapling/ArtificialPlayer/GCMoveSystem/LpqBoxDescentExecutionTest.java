package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.MapVFX.CustomReactor;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LpqBoxDescentExecutionTest {
    private static final int BOT_ID = 892001;
    private static final int BOX_OID = 99109;
    private static final Point BOX = new Point(-170, -1860);
    private static final Method TICK;
    @TempDir static Path navigationCache;
    private static Path originalCache;
    private static MapleMap tower;
    private Character bot;
    private BotMovementState state;

    static {
        try {
            TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            TICK.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    static void bootHost() throws Exception {
        LPQStage5TopDoorExecutionTest.stub();
        originalCache = BotNavigationGraphProvider.cacheDir();
        BotNavigationGraphProvider.setCacheDir(navigationCache);
        tower = BotNavigationMapLoader.loadMapGeometry(922010200);
        BotNavigationGraphProvider.rebuildGraph(tower, BotMovementProfile.base());
        Reactor box = mock(Reactor.class);
        when(box.getId()).thenReturn(2202003);
        when(box.getObjectId()).thenReturn(BOX_OID);
        when(box.getType()).thenReturn(MapObjectType.REACTOR);
        when(box.getPosition()).thenReturn(BOX);
        when(box.isActive()).thenReturn(true);
        Field objects = MapleMap.class.getDeclaredField("mapobjects");
        objects.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, MapObject> mapObjects = (Map<Integer, MapObject>) objects.get(tower);
        mapObjects.put(BOX_OID, box);
    }

    @AfterAll
    static void restoreCache() {
        if (originalCache != null) BotNavigationGraphProvider.setCacheDir(originalCache);
        MovementClock.reset();
    }

    @BeforeEach
    void useClock() {
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterEach
    void releaseBot() throws Exception {
        if (bot != null) GCMovement.stop(bot);
        states().remove(BOT_ID);
        PqActions.clearReactorBeat(BOT_ID);
        PqActions.clearSeekState(BOT_ID);
        MovementClock.reset();
    }

    @ParameterizedTest
    @CsvSource({"-154, 115", "-142, 115", "-118, 123"})
    void theUpperShelfFinishesItsDescentInsteadOfReversingEveryFewTicks(int x, int jump) throws Exception {
        // FH351 (B) can walk left to the box floor or right via FH350 (A). Both are legal,
        // but replacing one with the other mid-run keeps the bot on B forever.
        createBot(tower, new Point(x, -1958), new BotMovementProfile(120, jump));
        assertEquals(351, GCMovement.groundFoothold(tower, bot.getPosition()).getId());
        approachBoxToArrival();
    }

    @ParameterizedTest
    @CsvSource({"-100", "-65"})
    void theShelf65PixelsAboveTheBoxIsNotArrival(int x) throws Exception {
        createBot(tower, new Point(x, -1925), new BotMovementProfile(120, 123));
        assertEquals(350, GCMovement.groundFoothold(tower, bot.getPosition()).getId());
        assertEquals(PqActions.Approach.TRAVELLING,
                PqActions.approachReactor(bot, BOX_OID, true, true));
        assertNotNull(state.moveTarget, "the descent must stay armed on the upper shelf");
        approachBoxToArrival();
    }

    @Test
    void neitherReactorStrikePathMayHitFromTheUpperShelf() throws Exception {
        createBot(tower, new Point(-100, -1925), new BotMovementProfile(120, 123));
        assertEquals(PqActions.Approach.TRAVELLING,
                PqActions.approachReactor(bot, BOX_OID, true, true));
        try (var swings = mockStatic(BotAttack.class); var hits = mockStatic(CustomReactor.class)) {
            assertFalse(PqActions.hitReactor(bot, BOX_OID));
            assertFalse(PqActions.hitReactorIfInReach(bot, BOX_OID));
            assertTrue(PqActions.workReactorOnBeat(bot), "the combat beat must continue the descent");
            swings.verifyNoInteractions();
            hits.verifyNoInteractions();
        }
    }

    @Test
    void aBodyStillFallingTowardTheBoxMustNotCancelItsMove() throws Exception {
        createBot(tower, new Point(-150, -1890), new BotMovementProfile(120, 123));
        state.inAir = true;
        assertEquals(PqActions.Approach.TRAVELLING,
                PqActions.approachReactor(bot, BOX_OID, true, true));
        assertNotNull(state.moveTarget);
    }

    @Test
    void theBoxCanStillBeStruckFromItsOwnPlatform() throws Exception {
        createBot(tower, new Point(-150, -1860), new BotMovementProfile(120, 123));
        try (var swings = mockStatic(BotAttack.class); var hits = mockStatic(CustomReactor.class)) {
            assertTrue(PqActions.hitReactor(bot, BOX_OID));
            swings.verify(() -> BotAttack.basicSwing(bot));
            hits.verify(() -> CustomReactor.hitReactorWithScript(tower, BOX_OID, bot));
        }
    }

    @Test
    void arrivalDoesNotRequireTheSameFootholdSegment() throws Exception {
        // The large box platform is two connected segments: FH337 on the right, FH338 on
        // the left. Requiring foothold identity would reject a legitimate same-floor swing.
        createBot(tower, new Point(-90, -1860), new BotMovementProfile(120, 123));
        assertEquals(337, GCMovement.groundFoothold(tower, bot.getPosition()).getId());
        assertEquals(338, GCMovement.groundFoothold(tower, BOX).getId());
        assertEquals(PqActions.Approach.IN_POSITION,
                PqActions.approachReactor(bot, BOX_OID, true, true));
    }

    @Test
    void anUnbakedMapStillRejectsArrivalOnTheShelfAbove() throws Exception {
        MapleMap map = new MapleMap(922019963, 0, 0, 922010000, 0f);
        map.setFootholds(new FootholdTree(new Point(-400, -2200), new Point(400, 0)));
        map.getFootholds().insert(new Foothold(new Point(-130, -1925), new Point(-62, -1925), 1));
        map.getFootholds().insert(new Foothold(new Point(-210, -1860), new Point(-34, -1860), 2));
        Character unbaked = mock(Character.class);
        when(unbaked.getMap()).thenReturn(map);
        when(unbaked.getPosition()).thenReturn(new Point(-100, -1925));
        assertEquals(GCMovement.UNBAKED_REGION, GCMovement.peekRegionIdAt(map, BOX.x, BOX.y));
        try (var movement = mockStatic(GCMovement.class, CALLS_REAL_METHODS)) {
            // Isolate the arrival decision: issuing the rejected approach must not start
            // a background driver or warm this deliberately unbaked map.
            movement.when(() -> GCMovement.move(eq(unbaked), anyInt(), anyInt(), any(), any()))
                    .thenAnswer(inv -> null);
            assertEquals(PqActions.Approach.TRAVELLING,
                    PqActions.descendToFloorAerialTarget(unbaked, BOX));
        } finally {
            PqActions.clearSeekState(0);
        }
    }

    @Test
    void aChangedGoalOnTheSameDestinationPlatformStillReplans() throws Exception {
        createBot(tower, new Point(-142, -1958), new BotMovementProfile(120, 115));
        BotNavigationManager.resolveTarget(state, new Point(-200, -1860), true);
        BotNavigationGraph.Edge leftExit = state.navEdge;
        assertNotNull(leftExit);
        assertTrue(leftExit.launchStepX < 0);

        BotNavigationManager.resolveTarget(state, new Point(-40, -1860), true);
        assertNotSame(leftExit, state.navEdge, "a real target change is not a fixed-goal oscillation");
        assertTrue(state.navEdge.launchStepX > 0);
    }

    @Test
    void aStage5ShelfAlsoKeepsItsRouteToTheThirdDoor() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010500);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        createBot(map, new Point(-40, -1051), new BotMovementProfile(110, 123));
        Point door = map.getPortal("in03").getPosition();
        GCMovement.move(bot, door.x, door.y);
        for (int t = 0; t < 160 && state.moveTarget != null; t++) tick();
        Point end = bot.getPosition();
        assertTrue(Math.abs(end.x - door.x) <= 8 && Math.abs(end.y - door.y) <= 8,
                "a fixed door target must not oscillate between a jump and a walk-off; ended at " + end);
    }

    private void approachBoxToArrival() throws Exception {
        for (int t = 0; t < 160; t++) {
            if (t % 5 == 0) {
                PqActions.Approach approach = PqActions.approachReactor(bot, BOX_OID, true, true);
                assertNotEquals(PqActions.Approach.STUCK, approach);
                if (approach == PqActions.Approach.IN_POSITION) {
                    assertTrue(GCMovement.isGrounded(bot), "arrival requires a landed body");
                    assertEquals(BOX.y, bot.getPosition().y, "arrival must be on the box's floor");
                    return;
                }
            }
            tick();
        }
        fail("box9 was never reached from the shelf; ended at " + bot.getPosition());
    }

    private void tick() throws Exception {
        ObserverTracker.markObservedNow(bot.getMapId());
        try {
            TICK.invoke(null, state);
        } catch (InvocationTargetException e) {
            throw new AssertionError("movement tick failed", e.getCause());
        }
        MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
    }

    private void createBot(MapleMap map, Point start, BotMovementProfile profile) throws Exception {
        BotNavigationGraphProvider.getGraph(map, profile);
        AtomicReference<Point> position = new AtomicReference<>(new Point(start));
        bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(BOT_ID);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getHp()).thenReturn(5000);
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        when(bot.getPosition()).thenAnswer(inv -> position.get());
        doAnswer(inv -> { position.set(new Point(inv.getArgument(0))); return null; })
                .when(bot).setPosition(any(Point.class));
        state = new BotMovementState(bot, null);
        state.movementProfile = profile;
        state.lastMap = map;
        state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        state.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(state, bot, start);
        states().put(BOT_ID, state);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, BotMovementState> states() throws Exception {
        Field states = GCMovement.class.getDeclaredField("STATES");
        states.setAccessible(true);
        return (Map<Integer, BotMovementState>) states.get(null);
    }
}
