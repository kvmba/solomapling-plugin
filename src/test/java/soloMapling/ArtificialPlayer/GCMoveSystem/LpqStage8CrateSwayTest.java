package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LPQ stage 8 (922010800, the crate combination) sway regression.
 *
 * <p>The room's nine crates are short same-row shelves separated by ~8px gaps, stacked in rows
 * 39px apart. A walk step is ~6px, so the gap between two shelves on one row is INSIDE one step:
 * a bot walking off the end of a shelf simply steps across onto its neighbour and never falls.
 *
 * <p>Reported symptom: bots on the middle crates pace left and right for a long time (ended only
 * by the 8s no-progress give-up, after which the same route is replanned) instead of walking on to
 * the intended crate.
 *
 * <p>Root cause was in the baked graph, not in steering: {@code addDirectionalDropEdge} fired its
 * ballistic fall from the shelf's own end pixel. That pixel is one step short of where the bot
 * actually leaves the ground, so the sim crossed the 8px gap and reported a descent onto the row
 * below — a descent the bot can never perform, because its real walk-off stays on the same row.
 * Every such shelf got a DROP edge whose landing was on the same row (or the row above), and the
 * route through it oscillated: jump onto the neighbour, "walk off", land back on the row just
 * left, replan the identical two edges.
 *
 * <p>Two pins here:
 * <ul>
 *   <li>the bake no longer mints a walk-off edge where the live walk-off leaves the bot on the
 *       row it started on;</li>
 *   <li>every crate-to-crate trip on the room's own geometry arrives without swaying.</li>
 * </ul>
 */
class LpqStage8CrateSwayTest {

    private static final int MAP_ID = 922010800;
    private static final int TICKS = 20 * 60; // 60s of production ticks at TICK_MS=50
    /** Reversal tolerance: a couple of honest deceleration/course micro-flips, not a sway loop. */
    private static final int MAX_REVERSALS = 6;

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
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class),
                org.mockito.ArgumentMatchers.any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("messageSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("logSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("exceptionSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
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

    /**
     * The nine crate centres the quest's own script uses as its areas, in area order
     * ({@code LudiPqData.CRATE_SPOTS}). Kept as literals so this test pins the geometry it was
     * measured on, not a production table that may be re-tuned independently.
     */
    private static final int[][] CRATES = {
            {-217, -220}, {-150, -221}, {-217, -182}, {-150, -182}, {-83, -183},
            {-216, -143}, {-149, -143}, {-84, -144}, {-13, -143}};

    @Test
    void everyCrateToCrateTripArrivesWithoutSwaying() {
        int anomalies = 0;
        for (int[] from : CRATES) {
            for (int[] to : CRATES) {
                if (from == to) {
                    continue;
                }
                int[] r = runMove(from[0], from[1], to[0], to[1]);
                boolean arrived = r[3] < TICKS;
                if (!arrived || r[0] > MAX_REVERSALS) {
                    anomalies++;
                    System.out.printf("STAGE8-SWAY %s -> %s: reversals=%d ticks=%d park=(%d,%d)%n",
                            java.util.Arrays.toString(from), java.util.Arrays.toString(to),
                            r[0], r[3], r[1], r[2]);
                }
            }
        }
        assertEquals(0, anomalies,
                "crate-to-crate trips swayed or never arrived - see STAGE8-SWAY lines above");
    }

    /**
     * Direct pin on the bake: on the crate rows a "walk off this shelf" that the live walk-off
     * resolves back onto the SAME row must not become a DROP edge. The bot cannot fly that arc.
     */
    @Test
    void aWalkOffThatStaysOnItsOwnRowIsNotBakedAsADescent() {
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base());
        assertNotNull(graph, "the stage-8 graph must be baked");
        int step = BotPhysicsEngine.walkStep(map, BotMovementProfile.base());
        int checked = 0;
        for (int[] crate : CRATES) {
            for (int dir : new int[]{-1, 1}) {
                Point start = new Point(crate[0], crate[1]);
                BotPhysicsEngine.WalkOffLanding live = BotPhysicsEngine.simulateWalkOffLanding(
                        map, start, dir, BotPhysicsEngine.initialGroundTravelState(start),
                        BotMovementProfile.base());
                if (live == null || live.landing().point().y > live.launchPoint().y + 4) {
                    continue; // this side really does fall; a DROP edge is legitimate there
                }
                checked++;
                int fromRegion = graph.findRegionId(map, start);
                for (BotNavigationGraph.Edge e : graph.getOutgoing(fromRegion)) {
                    if (e.type != BotNavigationGraph.EdgeType.DROP || e.launchStepX != step * dir) {
                        continue;
                    }
                    Point ledge = dir < 0 ? graph.getRegion(fromRegion).leftPoint() : graph.getRegion(fromRegion).rightPoint();
                    assertTrue(!e.startPoint.equals(start) || e.endPoint.y > ledge.y + 4,
                            "a same-row walk-off (" + crate[0] + "," + crate[1] + ") dir " + dir
                                    + " was baked as a descent onto the row it never leaves: " + e.endPoint);
                }
            }
        }
        assertTrue(checked > 0, "no same-row walk-offs found - the stage-8 geometry changed");
    }

    /** Run the production tick chain (nav resolve -> physics -> watchdog -> arrival) to arrival. */
    private static int[] runMove(int startX, int startY, int goalX, int goalY) {
        BotMovementState st = botAt(startX, startY);
        st.moveTarget = new Point(goalX, goalY);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "test";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = System.currentTimeMillis();
        int reversals = 0;
        int lastSign = 0;
        int arrivalTick = -1;
        for (int t = 0; t < TICKS; t++) {
            int prevX = st.bot.getPosition().x;
            productionTick(st, t % 2 == 0);
            Point p = st.bot.getPosition();
            if (st.moveTarget == null) {
                arrivalTick = t;
                break;
            }
            int stepSign = Integer.compare(p.x - prevX, 0);
            if (stepSign != 0) {
                if (lastSign != 0 && stepSign != lastSign) {
                    reversals++;
                }
                lastSign = stepSign;
            }
        }
        Point end = st.bot.getPosition();
        return new int[]{reversals, end.x, end.y, arrivalTick < 0 ? TICKS : arrivalTick};
    }

    private static BotMovementState botAt(int x, int y) {
        org.gms.client.Character botMock = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        BotMovementState st = new BotMovementState(botMock, null);
        Point pos = new Point(x, y);
        when(st.bot.getMap()).thenReturn(map);
        when(st.bot.getMapId()).thenReturn(MAP_ID);
        when(st.bot.getHp()).thenReturn(50);
        when(st.bot.getChair()).thenReturn(0);
        when(st.bot.getPosition()).thenAnswer(inv -> new Point(pos));
        doAnswer(inv -> {
            Point next = inv.getArgument(0);
            pos.move(next.x, next.y);
            return null;
        }).when(st.bot).setPosition(any(Point.class));
        st.movementProfile = BotMovementProfile.base();
        st.lastMap = map;
        st.physX = x;
        st.physY = y;
        return st;
    }

    // Same order as GCMovementDriver.tick, minus the world-facing systems.
    private static void productionTick(BotMovementState st, boolean runAiTick) {
        Point target = st.moveTarget != null ? st.moveTarget : st.bot.getPosition();
        BotNavigationManager.NavigationDirective nav =
                BotNavigationManager.resolveTarget(st, target, runAiTick);
        if (nav.consumedTick) {
            return;
        }
        Point steering = nav.targetPos;
        if (st.moveTargetPrecise && st.navEdge == null) {
            st.navPreciseTarget = true;
        }
        if (st.climbing) {
            BotMovementManager.tickClimbing(st, steering, runAiTick);
        } else if (st.inAir) {
            BotMovementManager.tickAirborne(st, steering);
        } else {
            BotMovementManager.tickGrounded(st, steering);
        }
        if (runAiTick && !st.inAir && !st.climbing) {
            BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(st, target);
        }
        tickStuckDetection(st);
        GCMovementDriver.clearReachedMoveTarget(st);
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
