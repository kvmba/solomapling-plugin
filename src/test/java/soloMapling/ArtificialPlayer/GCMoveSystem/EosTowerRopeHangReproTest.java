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
 * 玩具塔 (Eos Tower 221020100..221024400) rope-hang repro: drive the PRODUCTION driver
 * tick over the REAL floor geometry with the training-bot movement pattern. The reported
 * bug: large numbers of bots hang mid-rope / at rope tops on floors 1-100 and never resume.
 *
 * Eos Tower floors are pure ladder-shafts: every rope is a LADDER (l=1) whose top lands on
 * a narrow two-tread landing, and floor rows sit ~150-300px apart vertically.
 */
public class EosTowerRopeHangReproTest {

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
            return mapSource.getData("Map/Map2/221020400.img") != null;
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

    private static void tickN(Sim sim, int ticks, int mapId) {
        for (int i = 0; i < ticks; i++) {
            if (i % 30 == 0) {
                ObserverTracker.markObservedNow(mapId);
            }
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            BotMovementState st = sim.st();
            if (i % 5 == 0) {
                sim.trace().append(String.format("t=%4d pos=%s nav=%-18s edge=%s block=%s air=%b climb=%b warmup=%b mt=%s%n",
                        i, sim.p(), st.lastNavDecision,
                        st.navEdge == null ? "-" : st.navEdge.type + "@" + st.navEdge.fromRegionId + "->" + st.navEdge.toRegionId
                                + (st.navEdge.launchStepX != 0 ? " lsx=" + st.navEdge.launchStepX : ""),
                        st.lastEdgeBlockReason,
                        st.inAir, st.climbing,
                        st.graphWarmupFallback, st.moveTarget));
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

    /**
     * Floor 5 (221020400): spawn near the bottom (st01 at y=1886), climb the bottom ladder
     * (x=3, span y 1904..2160) UP and then DOWN again — the pattern of a bot whose grind
     * target is the floor below. Hanging on the ladder and never resuming is the bug.
     */
    @Test
    void simFloor5LadderUpDown() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(221020400);
        System.out.println("== 221020400 ropes: " + map.getRopes().size());
        map.getRopes().forEach(r -> System.out.println(
                "   rope x=" + r.x() + " span=[" + Math.min(r.y1(), r.y2()) + "," + Math.max(r.y1(), r.y2()) + "] ladder=" + r.isLadder()));

        Sim sim = sim(221020400, 4, 1886, profile);
        // Leg 1: up to the landing at y~1886 above the bottom ladder (st01 level)
        move(sim.st(), 3, 1886);
        tickN(sim, 60, 221020400);
        Point afterUp = sim.p();
        System.out.println("floor5 up END " + afterUp);
        report("floor5 up", sim);

        // Leg 2: climb DOWN to the bottom floor row (y=2160)
        move(sim.st(), 3, 2150);
        tickN(sim, 60, 221020400);
        Point afterDown = sim.p();
        System.out.println("floor5 down END " + afterDown);
        report("floor5 down", sim);
        org.junit.jupiter.api.Assertions.assertTrue(afterDown.y >= 2100,
                "bot should reach the bottom row, ended at " + afterDown);
    }

    /**
     * Floor 5: cross-floor trek — from the st01 level to the TOP of the map (top00 portal
     * at (220,148)). A big map: the bot must chain ~6 ladders. The stuck-on-rope report.
     */
    @Test
    void simFloor5ClimbToTop() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(221020400, 4, 1886, profile);
        for (int beat = 0; beat < 24; beat++) {
            move(sim.st(), 220, 148);
            tickN(sim, 30, 221020400);
        }
        Point end = sim.p();
        System.out.println("floor5 -> top END " + end);
        report("floor5 -> top", sim);
        org.junit.jupiter.api.Assertions.assertTrue(end.y <= 400,
                "bot should climb to the top row, ended at " + end);
    }

    /**
     * Floor 100 (221024400): the deep descent — from the spawn (y=1435) DOWN to the
     * in00 portal at (168, 2). The map's ladder rows descend ~2000px.
     */
    @Test
    void simFloor100Descend() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(221024400, -162, 1435, profile);
        for (int beat = 0; beat < 20; beat++) {
            move(sim.st(), 168, 2);
            tickN(sim, 30, 221024400);
        }
        Point end = sim.p();
        System.out.println("floor100 descend END " + end);
        report("floor100 descend", sim);
        org.junit.jupiter.api.Assertions.assertTrue(end.y <= 300,
                "bot should descend to the in00 row (y=2), ended at " + end);
    }
}
