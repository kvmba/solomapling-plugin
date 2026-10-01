package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Execution-level repro for Tower's Maze (塔的迷路, LPQ stage-5 door room 922010501):
 * the reported "climbs the middle rope halfway, stalls, down-jumps, retries, gives up"
 * loop. Drives the PRODUCTION driver tick over the real WZ geometry exactly like
 * {@link LpqStage1ExecutionSimTest}.
 *
 * <p>The room's WZ shape (verified by dumping 922010501): the party enters at the top row
 * (y=-3510, out00), the four pass boxes sit on the -3378 island and the -2350/-2132/-1914
 * rows, and everything below the -2461 row chains DOWNWARD only — the middle rope's bottom
 * (y=-2500) hangs 39px above the -2461 row, so no row below can climb back. The room's
 * second exit out01 (y=-262) is the way out from the deep rows.
 */
public class TowerMazeExecutionSimTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
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
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(),
                "Map.wz not resolvable from this working directory (sim needs the real WZ)");
    }

    static boolean mapWzAvailable() {
        try {
            org.gms.provider.DataProvider mapSource =
                    org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return mapSource.getData("Map/Map9/922010501.img") != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos, StringBuilder trace) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(int mapId, int spawnX, int spawnY, BotMovementProfile profile) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotNavigationGraphProvider.getGraph(map, profile);

        AtomicReference<Point> pos = new AtomicReference<>(new Point(spawnX, spawnY));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getId()).thenReturn(777);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMapId = mapId;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(spawnX, spawnY));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        return new Sim(st, pos, new StringBuilder());
    }

    private static final java.lang.reflect.Method TICK;

    static {
        try {
            TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            TICK.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * @param realTimePacing true sleeps 50ms per tick so wall-clock gates (the 1s down-jump
     *                       cadence, the airborne 2s stall) elapse at the rate production sees.
     *                       The compressed default (no sleep) is fine for gates that tick-time.
     */
    private static void tickN(Sim sim, int ticks, boolean keepActive, int mapId, boolean realTimePacing) {
        for (int i = 0; i < ticks; i++) {
            if (keepActive && i % 30 == 0) {
                ObserverTracker.markObservedNow(mapId);
            }
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            if (realTimePacing) {
                try {
                    Thread.sleep(BotPhysicsEngine.cfg.TICK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            BotMovementState st = sim.st();
            if (i % 10 == 0) {
                sim.trace().append(String.format("t=%4d pos=%s nav=%-18s edge=%s block=%s air=%b climb=%b mt=%s%n",
                        i, sim.p(), st.lastNavDecision,
                        st.navEdge == null ? "-" : st.navEdge.type + "@" + st.navEdge.fromRegionId + "->" + st.navEdge.toRegionId,
                        st.lastEdgeBlockReason, st.inAir, st.climbing, st.moveTarget));
            }
        }
    }

    /** Mirrors GCMovement.move()'s state writes without the enable() detour. */
    private static void move(BotMovementState st, int x, int y) {
        st.following = false;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
        st.moveTarget = new Point(x, y);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = System.currentTimeMillis();
    }

    private void report(String label, Sim sim) {
        StringBuilder sb = new StringBuilder("=== " + label + " (end " + sim.p() + ")\n");
        java.util.Arrays.stream(sim.trace().toString().split("\n"))
                .limit(200)
                .forEach(l -> sb.append(l).append('\n'));
        System.out.println(sb);
    }

    /** PQ-realistic macro cadence: the quest layer re-issues the move every ~1.5s beat (30 ticks),
     * resetting moveProgressAtMs. Ticks run real-time so the wall-clock gates behave as in production. */
    private static void beats(Sim sim, int beats, int x, int y, int mapId) {
        for (int beat = 0; beat < beats; beat++) {
            move(sim.st(), x, y);
            tickN(sim, 30, true, mapId, true);
        }
    }

    /**
     * THE REPORTED FAILURE, pre-fix geometry: from the -2132 box row the graph has NO path up
     * to the -3378 island (the middle rope's bottom hangs above the -2461 row, unreachable from
     * below). The engine must answer no-path — the PQ layer then exits via the bottom out01
     * instead of stalling. Asserts the graph's verdict AND that the bot still ends the leg on
     * the -2132 row (no bogus raw-steer descent).
     */
    @Test
    void graphKnowsTheDeepRowsCannotClimbBack() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010501);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, profile);
        int deepRow = g.findRegionId(map, new Point(-101, -2132));
        int island = g.findRegionId(map, new Point(-106, -3378));
        System.out.println("501 regions deepRow=" + deepRow + " island=" + island);
        org.junit.jupiter.api.Assertions.assertNotEquals(island, deepRow,
                "island and deep row must be distinct regions");
        // No edge chain: findPath from the deep row must fail to reach the island's region.
        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(g, map,
                new Point(-101, -2132), deepRow, island, new Point(-106, -3378));
        org.junit.jupiter.api.Assertions.assertTrue(path == null || path.isEmpty(),
                "no edge chain may lead from the deep rows back up to the island");
    }

    /**
     * From the deep rows, the bot CAN path down to the bottom exit row (out01 at y=-262).
     * Every row-to-row down-jump on the descent chain executes: the pre-fix rescue-hop bounce
     * (cadence-hold treated as a wedge) is what broke these.
     */
    @Test
    void sim501PerRowDescents() {
        int[][] rows = {
                {-78, -2350, -50, -2241},
                {-50, -2241, -50, -2132},
                {-50, -2132, -50, -2023},
                {-50, -2023, -50, -1914},
                {-50, -1914, -50, -1805},
                {-50, -1805, -50, -259},
        };
        for (int[] row : rows) {
            BotMovementProfile profile = new BotMovementProfile(105, 110);
            Sim sim = sim(922010501, row[0], row[1], profile);
            // A full-rope descent (r11 -> r12, ~1500px at ~5px/tick) needs ~400 ticks.
            for (int beat = 0; beat < 16; beat++) {
                move(sim.st(), row[2], row[3]);
                tickN(sim, 30, true, 922010501, true);
            }
            Point end = sim.p();
            boolean ok = Math.abs(end.y - row[3]) <= 30;
            System.out.println("501 row " + row[1] + " -> " + row[3] + " END " + end + (ok ? " OK" : " FAIL"));
            if (!ok) {
                report("501 row " + row[1] + "->" + row[3], sim);
            }
            org.junit.jupiter.api.Assertions.assertTrue(ok,
                    "row " + row[1] + " -> " + row[3] + " descent failed, ended at " + end);
        }
    }

    /**
     * The island box leg: enter at the top row, drop to the island, work the box spot — the
     * first leg of the room's intended top-down flow.
     */
    @Test
    void sim501TopRowToIslandBox() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(922010501, -194, -3513, profile);
        beats(sim, 8, -106, -3378, 922010501);
        Point end = sim.p();
        boolean ok = Math.abs(end.x - -106) <= 30 && Math.abs(end.y - -3378) <= 30;
        System.out.println("501 island box leg END " + end + (ok ? " OK" : " FAIL"));
        report("501 top->island", sim);
        org.junit.jupiter.api.Assertions.assertTrue(ok,
                "bot should reach the island box spot (-106,-3378), ended at " + end);
    }

    /**
     * The island leg down to the first deep box row: rope x=15 down to -2500, step off to the
     * -2461 row, down-jump to -2350.
     */
    @Test
    void sim501IslandToFirstDeepBoxRow() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(922010501, -106, -3378, profile);
        beats(sim, 14, -78, -2350, 922010501);
        Point end = sim.p();
        boolean ok = Math.abs(end.y - -2350) <= 30;
        System.out.println("501 island->r6 box row END " + end + (ok ? " OK" : " FAIL"));
        report("501 island->r6", sim);
        org.junit.jupiter.api.Assertions.assertTrue(ok,
                "bot should descend from the island to the -2350 box row, ended at " + end);
    }

    /**
     * THE FIX'S PAYLOAD: a bot on the deep rows paths DOWN to the bottom exit row (out01,
     * 193,-262) in one committed chain, instead of bouncing against an unreachable goal.
     */
    @Test
    void sim501DeepRowToBottomExitRow() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(922010501, -101, -2132, profile);
        beats(sim, 20, 193, -262, 922010501);
        Point end = sim.p();
        boolean ok = Math.abs(end.x - 193) <= 40 && Math.abs(end.y - -262) <= 40;
        System.out.println("501 deep->out01 row END " + end + (ok ? " OK" : " FAIL"));
        report("501 deep->out01", sim);
        org.junit.jupiter.api.Assertions.assertTrue(ok,
                "bot should reach the out01 row (-262), ended at " + end);
    }

    /** 503 (对比组): the two-rope room is two-way — full spawn->middle->top climb works. */
    @Test
    void sim503FullClimbStillWorks() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(922010503, 0, -221, profile);
        beats(sim, 40, -147, -3535, 922010503);
        Point end = sim.p();
        boolean ok = Math.abs(end.y - -3535) <= 30;
        System.out.println("503 spawn->top END " + end + (ok ? " OK" : " FAIL"));
        org.junit.jupiter.api.Assertions.assertTrue(ok,
                "bot should reach 503's top row (y=-3535), ended at " + end);
    }
}
