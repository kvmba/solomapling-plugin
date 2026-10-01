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
 * 玩具塔 grind-loop repro: a TrainingBot-style loop (roam/stack re-issues move every
 * 250ms combat beat) on the REAL Eos Tower floor geometry. The reported bug is that
 * many bots end up HANGING on the ladders. This sim drives the full loop and pins
 * where each profile ends up.
 */
public class EosTowerGrindRopeHangReproTest {

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
        st.grinding = true;
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

    private static void report(String label, Sim sim, int ticks) {
        StringBuilder sb = new StringBuilder("=== " + label + " (end " + sim.p() + ")\n");
        java.util.Arrays.stream(sim.trace().toString().split("\n"))
                .limit(200)
                .forEach(l -> sb.append(l).append('\n'));
        System.out.println(sb);
    }

    /**
     * The grind-loop shape: every 5 ticks (=250ms combat beat) the layer re-issues
     * GCMovement.move to a target on ANOTHER floor of the map (mobs on both). The bot
     * repeatedly pathfinds, climbs, dismounts, and re-climbs. Each beat also writes
     * lastClimbY-ish fresh state. Watch for the end state: hung on a rope forever?
     */
    @Test
    void simGrindLoopCrossFloor() {
        for (int[] stat : new int[][]{{105, 110}, {120, 115}}) {
            BotMovementProfile profile = new BotMovementProfile(stat[0], stat[1]);
            Sim sim = sim(221020400, 4, 1886, profile);
            StringBuilder trace = new StringBuilder();
            // 240 beats * 5 ticks = 1200 ticks = 60s of grind
            for (int beat = 0; beat < 240; beat++) {
                // mobs alternate between the y=430 top row and the y=1638 bottom row
                Point target = (beat % 12 < 6) ? new Point(140, 430) : new Point(76, 1638);
                move(sim.st(), target.x, target.y);
                Point before = sim.p();
                tickN(sim, 5, 221020400);
                Point after = sim.p();
                trace.append(String.format("beat=%3d tgt=(%d,%d) %s -> %s climb=%b air=%b edge=%s nav=%s block=%s%n",
                        beat, target.x, target.y, before, after,
                        sim.st().climbing, sim.st().inAir,
                        sim.st().navEdge == null ? "-" : sim.st().navEdge.type + "@" + sim.st().navEdge.fromRegionId + "->" + sim.st().navEdge.toRegionId,
                        sim.st().lastNavDecision,
                        sim.st().lastEdgeBlockReason));
            }
            Point end = sim.p();
            BotMovementState st = sim.st();
            System.out.println("grind-loop " + stat[0] + "/" + stat[1] + " END " + end
                    + " climbing=" + st.climbing + " inAir=" + st.inAir);
            System.out.println(trace);
            org.junit.jupiter.api.Assertions.assertFalse(st.climbing,
                    "profile " + stat[0] + "/" + stat[1] + " ended mid-rope at " + end);
        }
    }
}
