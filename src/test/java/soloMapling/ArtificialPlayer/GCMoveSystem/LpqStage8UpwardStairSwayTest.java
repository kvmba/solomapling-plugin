package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LPQ stage 8 (922010800) upward-sway regression: a bot climbing from the bottom floor to the
 * crate areas paces left-right on the dense stair band and is then rescue-hopped.
 *
 * <p>The room's stairs are a lattice of 60px shelves joined by 15px ramps (fh222/226/230, slope
 * 0.87) that step 13px per shelf. The takeoff window of the fh210 -> fh251 jump is only 4 px wide
 * (x 44..47): the arc from 43 or earlier lands back on fh224, from 48 or later also lands on
 * fh224. {@code insetJumpLaunchWindow} used to collapse a window that thin to its CENTRE pixel.
 * A bot at running speed strides ~6px per tick, so it stepped straight over x=45 and oscillated
 * between x=41 and x=43 — the reported "低处往上走、路过密集台阶时原地左右摇摆" — until the
 * 500 ms no-progress watchdog rescue-hopped it.
 *
 * <p>Pins:
 * <ul>
 *   <li>the pure inset rule: an INTERIOR thin window keeps its whole validated span, while an
 *       edge-pressed one still collapses (that collapse is what keeps a small-ledge jump from
 *       overflying its target);</li>
 *   <li>the end-to-end climb: bottom floor to every crate, no sway, no rescue hop.</li>
 * </ul>
 */
class LpqStage8UpwardStairSwayTest {

    private static final int MAP_ID = 922010800;
    private static final int TICKS = 1500;
    private static MapleMap map;

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
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
    }

    static org.gms.dao.entity.GameConfigDO cfg(String t, String k, String v) {
        org.gms.dao.entity.GameConfigDO d = new org.gms.dao.entity.GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v); d.setConfigClazz("java.lang.Long");
        return d;
    }

    /** A ground region spanning [minX,maxX] at y (one horizontal foothold). */
    private static BotNavigationGraph.Region region(int minX, int maxX, int y) {
        Foothold fh = new Foothold(new Point(minX, y), new Point(maxX, y), 1);
        return new BotNavigationGraph.Region(1, List.of(new BotNavigationGraph.Segment(fh, false)));
    }

    @Test
    void anInteriorThinWindowKeepsItsWholeSpan() {
        BotNavigationGraph.Region r = region(-100, 100, 0);
        // [44..47] is the stage-8 fh210 -> fh251 takeoff span: 4px, interior.
        int[] w = BotNavigationGraphProvider.insetJumpLaunchWindow(r, 44, 47,
                BotNavigationGraphProvider.jumpLaunchMargin(null, BotMovementProfile.base()));
        assertEquals(44, w[0], "an interior thin window must keep its validated min");
        assertEquals(47, w[1], "an interior thin window must keep its validated max");
    }

    @Test
    void anEdgePressedThinWindowStillCollapses() {
        BotNavigationGraph.Region r = region(-100, 220, 0);
        int[] w = BotNavigationGraphProvider.insetJumpLaunchWindow(r, 218, 220,
                BotNavigationGraphProvider.jumpLaunchMargin(null, BotMovementProfile.base()));
        assertEquals(w[0], w[1], "an edge-pressed thin window still collapses to one point");
        assertTrue(w[1] < 220, "and that point sits inside the ledge");
    }

    @Test
    void climbingFromTheBottomToEveryCrateDoesNotSway() {
        int[] starts = {-240, -200, -160, -120, -80, -40, 0, 40, 80, 120, 160, 200};
        int[][] crates = {
                {-217, -220}, {-150, -221}, {-217, -182}, {-150, -182}, {-83, -183},
                {-216, -143}, {-149, -143}, {-84, -144}, {-13, -143}};
        int anomalies = 0;
        for (int sx : starts) {
            Point start = BotPhysicsEngine.findGroundPoint(map, new Point(sx, 454));
            if (start == null) {
                continue;
            }
            for (int[] c : crates) {
                int[] r = climb(start.x, start.y, c[0], c[1]);
                boolean arrived = r[3] < TICKS;
                boolean swayed = r[0] > 12;
                if (!arrived || swayed) {
                    anomalies++;
                    System.out.printf("UP-SWAY start=(%d,%d) crate=(%d,%d) ticks=%d rev=%d park=(%d,%d)%n",
                            start.x, start.y, c[0], c[1], r[3], r[0], r[1], r[2]);
                }
            }
        }
        assertEquals(0, anomalies,
                "bottom-to-crate climbs swayed or never arrived - see UP-SWAY lines above");
    }

    /** Production tick chain (nav resolve -> physics -> stuck watchdog -> arrival). */
    private static int[] climb(int sx, int sy, int gx, int gy) {
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
        st.movementProfile = BotMovementProfile.base();
        st.lastMap = map;
        st.physX = sx;
        st.physY = sy;
        st.moveTarget = new Point(gx, gy);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "test";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = System.currentTimeMillis();

        int rev = 0, last = 0, arrival = -1;
        for (int t = 0; t < TICKS; t++) {
            int px = st.bot.getPosition().x;
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
                tickStuckDetection(st);
            }
            GCMovementDriver.clearReachedMoveTarget(st);
            Point q = st.bot.getPosition();
            if (st.moveTarget == null) {
                arrival = t;
                break;
            }
            int sign = Integer.compare(q.x, px);
            if (sign != 0) {
                if (last != 0 && sign != last) {
                    rev++;
                }
                last = sign;
            }
        }
        Point end = st.bot.getPosition();
        return new int[]{rev, end.x, end.y, arrival < 0 ? TICKS : arrival};
    }

    // Mirrors GCMovementDriver.tickStuckDetection (the parts that touch only entry + position).
    private static void tickStuckDetection(BotMovementState st) {
        st.unstuckCooldownMs = BotMovementManager.tickDown(st.unstuckCooldownMs);
        if (BotMovementManager.isStuckCheckExempt(st)) {
            st.stuckMs = 0;
            st.stuckCheckX = Integer.MIN_VALUE;
            return;
        }
        Point botPos = st.bot.getPosition();
        if (st.stuckCheckX == Integer.MIN_VALUE) {
            st.stuckCheckX = botPos.x;
            st.stuckCheckY = botPos.y;
            return;
        }
        boolean moved;
        if (st.moveTarget != null && st.moveTargetPrecise && st.navEdge == null
                && Math.abs(st.moveTarget.x - st.stuckCheckX) <= 60
                && Math.abs(st.moveTarget.y - st.stuckCheckY) <= 60) {
            int anchorDist = Math.abs(st.moveTarget.x - st.stuckCheckX)
                    + Math.abs(st.moveTarget.y - st.stuckCheckY);
            int botDist = Math.abs(st.moveTarget.x - botPos.x)
                    + Math.abs(st.moveTarget.y - botPos.y);
            moved = botDist < anchorDist - 8;
        } else {
            moved = Math.abs(botPos.x - st.stuckCheckX) > 8 || Math.abs(botPos.y - st.stuckCheckY) > 8;
        }
        if (moved) {
            st.stuckMs = 0;
            st.stuckCheckX = botPos.x;
            st.stuckCheckY = botPos.y;
        } else {
            st.stuckMs += BotPhysicsEngine.cfg.TICK_MS;
        }
        if (st.stuckMs >= 500 && st.unstuckCooldownMs == 0) {
            st.stuckMs = 0;
            st.stuckCheckX = Integer.MIN_VALUE;
            BotMovementManager.tickUnstuck(st);
        }
    }
}
