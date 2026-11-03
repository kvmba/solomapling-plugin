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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LPQ stage 8 (922010800) "shelf step -> large platform" sway regression.
 *
 * <p>The room's mid lattice stacks a wide tread under a narrow one and the graph baker unions
 * both into ONE region (r27 = fh169 + fh192 + fh227 at y=118, plus the fh226 ramp down to fh233
 * at y=131). A JUMP edge baked from that region carries a launch window whose pixels belong to
 * the UPPER tread; a bot standing on the LOWER tread still passes the window's X test, because
 * {@code Region.pointAt(x)} answers the segment nearest on X and ignores the bot's own Y.
 *
 * <p>Result: the bot is told "you are in the launch window", walks to the window's X range, and
 * then {@code arcLaunchesFrom} refuses every launch there (the arc from the lower tread falls
 * back onto the lower tread). It paces across the window's X span forever, until the 500 ms
 * no-progress watchdog rescue-hops it — the reported
 * "路过货架式台阶、跳向大平台时原地左右摆动N次, 持续N秒后才跳走".
 *
 * <p>Pins:
 * <ul>
 *   <li>the window's Y gate must reject a bot standing on another tread of the same region;</li>
 *   <li>the end-to-end climb over the named steps fires the jump with no rescue hop.</li>
 * </ul>
 */
class LpqStage8ShelfTreadSwayTest {

    private static final int MAP_ID = 922010800;
    private static final int TICKS = 20 * 60;
    private static MapleMap map;
    /** 120/115 = thief with Haste: walkStep 8, the profile the room's stairs are baked for. */
    private static final BotMovementProfile PROFILE = new BotMovementProfile(120, 115);

    @BeforeAll
    static void boot() {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<org.gms.dao.entity.GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource ms = mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        try {
            var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
            setter.setAccessible(true);
            setter.invoke(new ServerManager(), ctx);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        org.gms.config.GameConfig.add(cfg("server", "update_interval", "100"));
        map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraphProvider.getGraph(map, PROFILE);
    }

    static org.gms.dao.entity.GameConfigDO cfg(String t, String k, String v) {
        org.gms.dao.entity.GameConfigDO d = new org.gms.dao.entity.GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v); d.setConfigClazz("java.lang.Long");
        return d;
    }

    /**
     * The waypoint must lead the bot to the window's ROW, not park it on the row below.
     *
     * <p>This is the pinned contract. The bot starts on the y=131 tread of r27 while the window
     * lives on the y=118 tread, 13px up, reachable only by walking back to the fh226 ramp. Asking
     * for the straight-line waypoint hands back a pixel on the bot's own row and the X-only steer
     * then reports zero error - the "货架式台阶跳大平台时原地左右摆动" report. The waypoint must
     * therefore name a pixel on the WINDOW's row.
     */
    @Test
    void theWaypointLeadsToTheWindowsOwnRow() {
        BotNavigationGraph g = BotNavigationGraphProvider.peekGraph(map, PROFILE);
        BotNavigationGraph.Edge edge = edge27to23(g);
        assertTrue(edge != null, "the r27 -> r23 jump must be baked");

        BotNavigationGraph.Region region = g.getRegion(27);
        Point waypoint = BotNavigationManager.selectJumpWaypoint(g, new Point(45, 131), edge);
        BotNavigationGraph.Segment tread = region.segmentAt(waypoint.x, waypoint.y);
        int windowY = region.segmentAt(edge.launchMinX, edge.startPoint.y).pointAt(edge.launchMinX).y;
        assertTrue(tread != null && tread.pointAt(waypoint.x).y == windowY,
                "the waypoint from the lower tread must aim at the window's row (y=" + windowY
                        + "), got " + waypoint);
    }

    /** And that means the executor can actually fire from the upper tread but not the lower one. */
    @Test
    void onlyTheWindowsOwnTreadCanLaunch() {
        BotNavigationGraph g = BotNavigationGraphProvider.peekGraph(map, PROFILE);
        BotNavigationGraph.Edge edge = edge27to23(g);
        assertTrue(BotNavigationManager.canExecuteJumpFromCurrentPosition(g, map, new Point(45, 118), edge),
                "the arc from the window's tread must reach the target region");
        assertFalse(BotNavigationManager.canExecuteJumpFromCurrentPosition(g, map, new Point(45, 131), edge),
                "the arc from the lower tread falls back onto the lower tread");
    }

    /** The end-to-end climb across the named steps must not need a rescue hop. */
    @Test
    void crossingTheShelfStepsNeverNeedsARescueHop() {
        int[][] cases = {
                // 84..106 tread (fh233, y=131) -> up to the crate row: crosses the w[42..49] window.
                {100, 131, 150, -106},
                {90, 131, 150, -106},
                // The two shapes the player named: over fh226/fh223 ramps onto the 23px buffer treads.
                {100, 355, 10, 230},
                {-50, 243, 10, 230},
                // The full climb out of the lower bank.
                {-150, 342, 150, -106},
                {60, 131, -217, -220},
        };
        for (int[] c : cases) {
            Point start = BotPhysicsEngine.findGroundPoint(map, new Point(c[0], c[1]));
            if (start == null || Math.abs(start.y - c[1]) > 4) {
                continue; // this column is not on the row the case names
            }
            Run r = drive(PROFILE, start.x, start.y, c[2], c[3]);
            assertEquals(0, r.rescues,
                    "a rescue hop fired climbing from (" + start.x + "," + start.y
                            + ") to (" + c[2] + "," + c[3] + "): the bot paced in front of a launch window"
                            + " it can never fire from (see the per-tread gate above)");
        }
    }

    private static BotNavigationGraph.Edge edge27to23(BotNavigationGraph g) {
        List<BotNavigationGraph.Edge> out = g.getOutgoing(27);
        if (out == null) {
            return null;
        }
        for (BotNavigationGraph.Edge e : out) {
            if (e.type == BotNavigationGraph.EdgeType.JUMP && e.toRegionId == 23 && e.launchMinX == 42) {
                return e;
            }
        }
        return null;
    }

    private record Run(int arrival, int rescues) {
    }

    private static Run drive(BotMovementProfile prof, int sx, int sy, int gx, int gy) {
        MovementClock.reset();
        MovementClock.useVirtual(1_000_000L);

        org.gms.client.Character botMock = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        BotMovementState st = new BotMovementState(botMock, null);
        Point pos = new Point(sx, sy);
        when(st.bot.getMap()).thenReturn(map);
        when(st.bot.getMapId()).thenReturn(MAP_ID);
        when(st.bot.getHp()).thenReturn(50);
        when(st.bot.getChair()).thenReturn(0);
        when(st.bot.getPosition()).thenAnswer(inv -> new Point(pos));
        doAnswer(inv -> { Point n = inv.getArgument(0); pos.move(n.x, n.y); return null; })
                .when(st.bot).setPosition(any(Point.class));
        st.movementProfile = prof;
        st.lastMap = map;
        st.physX = sx;
        st.physY = sy;
        st.moveTarget = new Point(gx, gy);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "test";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = MovementClock.nowMs();
        st.stuckCheckX = Integer.MIN_VALUE;

        int arrival = -1, rescues = 0;
        for (int t = 0; t < TICKS; t++) {
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            Point target = st.moveTarget != null ? st.moveTarget : st.bot.getPosition();
            BotNavigationManager.NavigationDirective nav =
                    BotNavigationManager.resolveTarget(st, target, t % 2 == 0);
            if (!nav.consumedTick) {
                Point steering = nav.targetPos;
                if (st.moveTargetPrecise && st.navEdge == null) {
                    st.navPreciseTarget = true;
                }
                if (st.climbing) {
                    BotMovementManager.tickClimbing(st, steering, t % 2 == 0);
                } else if (st.inAir) {
                    BotMovementManager.tickAirborne(st, steering);
                } else {
                    BotMovementManager.tickGrounded(st, steering);
                }
                if (t % 2 == 0 && !st.inAir && !st.climbing) {
                    BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(st, target);
                }
                if (tickStuckDetection(st)) {
                    rescues++;
                }
            }
            GCMovementDriver.clearReachedMoveTarget(st);
            if (st.moveTarget == null && !st.inAir) {
                arrival = t;
                break;
            }
        }
        return new Run(arrival, rescues);
    }

    /** Mirrors GCMovementDriver.tickStuckDetection exactly (the parts that touch entry only). */
    private static boolean tickStuckDetection(BotMovementState st) {
        st.unstuckCooldownMs = BotMovementManager.tickDown(st.unstuckCooldownMs);
        if (BotMovementManager.isStuckCheckExempt(st)) {
            st.stuckMs = 0;
            st.stuckCheckX = Integer.MIN_VALUE;
            return false;
        }
        Point p = st.bot.getPosition();
        if (st.stuckCheckX == Integer.MIN_VALUE) {
            st.stuckCheckX = p.x;
            st.stuckCheckY = p.y;
            return false;
        }
        boolean moved;
        if (st.moveTarget != null && st.moveTargetPrecise && st.navEdge == null
                && Math.abs(st.moveTarget.x - st.stuckCheckX) <= 60
                && Math.abs(st.moveTarget.y - st.stuckCheckY) <= 60) {
            int a = Math.abs(st.moveTarget.x - st.stuckCheckX) + Math.abs(st.moveTarget.y - st.stuckCheckY);
            int b = Math.abs(st.moveTarget.x - p.x) + Math.abs(st.moveTarget.y - p.y);
            moved = b < a - 8;
        } else {
            moved = Math.abs(p.x - st.stuckCheckX) > 8 || Math.abs(p.y - st.stuckCheckY) > 8;
        }
        if (moved) {
            st.stuckMs = 0;
            st.stuckCheckX = p.x;
            st.stuckCheckY = p.y;
            return false;
        }
        st.stuckMs += BotPhysicsEngine.cfg.TICK_MS;
        if (st.stuckMs >= 500 && st.unstuckCooldownMs == 0) {
            st.stuckMs = 0;
            st.stuckCheckX = Integer.MIN_VALUE;
            BotMovementManager.tickUnstuck(st);
            return true;
        }
        return false;
    }
}
