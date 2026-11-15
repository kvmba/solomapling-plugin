package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real stage-3 geometry: the right extension (fh269) shares x=265 with the inner wall. */
class LpqStage3RightPlatformTest {
    @BeforeAll
    static void stub() throws Exception {
        LpqStage3PitEntryTest.stub();
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    private static BotMovementState entry(MapleMap map, Point start, BotMovementProfile profile) {
        AtomicReference<Point> pos = new AtomicReference<>(new Point(start));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getId()).thenReturn(8300 + start.x);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getJob()).thenReturn(null);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());
        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, start);
        BotMovementManager.resetEntryStateAfterTeleport(st);
        return st;
    }

    @Test
    void aFallAgainstTheInnerWallStillLandsOnThePlatform() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010300);
        for (int endX : new int[]{264, 265}) {
            BotPhysicsEngine.AirCollision collision = BotPhysicsEngine.resolveAirCollision(
                    map, new Point(266, -1250), new Point(endX, -1214));
            assertEquals(BotPhysicsEngine.AirCollisionType.LAND, collision.type());
            assertEquals(-1247, collision.point().y);
            // fh267 and fh269 share x=265: either endpoint owner is valid, crossing left is not.
            assertTrue(collision.point().x >= 265 && collision.point().x <= 266,
                    "land on the extension without moving through the wall");
        }
    }

    @Test
    void theObservedDriverLandsBeforeApplyingTheWallClamp() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010300);
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        Point start = new Point(266, -1250);
        BotMovementState st = entry(map, start, BotMovementProfile.base());
        // Recreate the descending, left-held contact step after a retry jump.
        BotPhysicsEngine.beginGroundJump(st, st.bot, -1);
        st.velY = 35f;
        st.moveDir = -1;
        st.fixedAirArc = true;
        st.moveTarget = new Point(163, -640);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveProgressAtMs = MovementClock.nowMs();
        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
        ObserverTracker.markObservedNow(map);
        tick.invoke(null, st);
        assertFalse(st.inAir, "the observed physics must catch the platform, not slide below it");
        assertEquals(-1247, st.bot.getPosition().y);
        assertTrue(st.bot.getPosition().x >= 265 && st.bot.getPosition().x <= 266);
    }

    @Test
    void repeatedOrdersFromTheRightPlatformNeverFallBelowTheStageFloor() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010300);
        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
        StringBuilder failures = new StringBuilder();
        for (BotMovementProfile profile : new BotMovementProfile[]{
                BotMovementProfile.base(), new BotMovementProfile(140, 120)}) {
            BotNavigationGraphProvider.getGraph(map, profile);
            for (int startX : new int[]{232, 264, 300, 347}) {
                for (Point goal : new Point[]{
                        new Point(163, -640), new Point(-139, -1135), new Point(-65, -1563)}) {
                    Point start = new Point(startX, -1247);
                    BotMovementState st = entry(map, start, profile);
                    StringBuilder trace = new StringBuilder();
                    boolean sawAirborne = false;
                    for (int i = 0; i < 400; i++) {
                        st.following = false;
                        st.farmAnchor = null;
                        st.farmAnchorMapId = -1;
                        st.moveTarget = goal;
                        st.moveTargetPrecise = true;
                        st.moveTargetSource = "gcmove";
                        st.moveBestDist = Integer.MAX_VALUE;
                        st.moveProgressAtMs = MovementClock.nowMs();
                        ObserverTracker.markObservedNow(map);
                        tick.invoke(null, st);
                        MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
                        Point p = st.bot.getPosition();
                        sawAirborne |= st.inAir;
                        if (i % 20 == 0 || st.downJumpPending) {
                            trace.append(i).append(':').append(p).append(' ')
                                    .append(st.lastNavDecision).append(' ')
                                    .append(st.lastEdgeBlockReason).append(" air=").append(st.inAir)
                                    .append(" down=").append(st.downJumpPending)
                                    .append(" edge=").append(st.navEdge).append('\n');
                        }
                        if (p.y > -160) {
                            failures.append(profile).append(' ').append(start).append(" -> ")
                                    .append(goal).append(" fell at tick ").append(i).append(" to ")
                                    .append(p).append('\n').append(trace);
                            break;
                        }
                        if (!st.inAir && !st.climbing && p.y == goal.y && Math.abs(p.x - goal.x) <= 8) {
                            break;
                        }
                    }
                    assertTrue(sawAirborne, "the orders must exercise real jumps or descents: "
                            + profile + " " + start + " -> " + goal);
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }
}
