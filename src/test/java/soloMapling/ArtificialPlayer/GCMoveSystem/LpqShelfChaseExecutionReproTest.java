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
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REGRESSION (2026-10-02 report, the old "小步停顿走路 + 不上跳平台打怪" shape returned on the
 * deploy box at df70153 while 662008e was clean): the chase layer (PqActions.seekAndAttack)
 * now finds the -2288/-1924 mob shelves PATHABLE (the v67 one-way-island repair) and aims the
 * seek move at the mob's floor point. This sim drives the PRODUCTION driver tick against that
 * seek from the row above, at the PQ combat-sweep cadence (a move re-issue every ~30 ticks),
 * and asserts the bot actually lands on the shelf it is chasing — execution, not just graph
 * reachability, is the contract.
 */
public class LpqShelfChaseExecutionReproTest {

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
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(), "Map.wz needed");
    }

    static boolean mapWzAvailable() {
        try {
            org.gms.provider.DataProvider s = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return s.getData("Map/Map9/922010100.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static GameConfigDO config(String type, String key, String value) {
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

    private static Sim sim(BotMovementProfile profile) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraphProvider.getGraph(map, profile);

        AtomicReference<Point> pos = new AtomicReference<>(new Point(0, 130));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(922010100);
        when(bot.getId()).thenReturn(778);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        org.mockito.Mockito.doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMapId = 922010100;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(0, 130));
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

    private static void tickN(Sim sim, int ticks, boolean keepActive) {
        for (int i = 0; i < ticks; i++) {
            if (keepActive && i % 30 == 0) {
                ObserverTracker.markObservedNow(922010100);
            }
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            BotMovementState st = sim.st();
            if (i % 5 == 0) {
                sim.trace().append(String.format("t=%4d pos=%s nav=%-16s edge=%s block=%s air=%b climb=%b mt=%s%n",
                        i, sim.p(), st.lastNavDecision,
                        st.navEdge == null ? "-" : st.navEdge.type + "@" + st.navEdge.fromRegionId + "->" + st.navEdge.toRegionId,
                        st.lastEdgeBlockReason, st.inAir, st.climbing, st.moveTarget));
            }
        }
    }

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

    /** The chase legs the v67 repair promised are executable, from the GROUND (the reported
     * symptom: mob on an upper shelf, bot below chases but never goes up). The route to a
     * -2288/-1924 shelf is ropes UP to the -2552 row, then a straight DROP onto the shelf. */
    @Test
    void chaseFromRowAboveLandsOnThe2288Shelf() {
        // (fromX, fromY, mobX, mobY): ground spawn and the row above, chasing the two
        // shelves the one-way-island repair serves.
        int[][] legs = {
                {0, 130, 117, -2311},      // full route: ground -> ropes up -> drop onto -2288
                {0, 130, -139, -2138},     // full route to the -1924 family shelf
                {124, -2581, 117, -2311},  // short leg: row above -> shelf (the repair edge)
        };
        int[] speeds = {105, 120};
        for (int stat : speeds) {
            BotMovementProfile profile = new BotMovementProfile(stat, 110);
            for (int[] leg : legs) {
                Sim sim = sim(profile);
                sim.pos().set(new Point(leg[0], leg[1]));
                BotPhysicsEngine.teleportTo(sim.st(), sim.st().bot, new Point(leg[0], leg[1]));
                BotMovementManager.resetEntryStateAfterTeleport(sim.st());
                // PQ cadence: re-issue the seek move every 30 ticks (~1.5s at TICK_MS=50),
                // for ~40s total.
                for (int burst = 0; burst < 40; burst++) {
                    move(sim.st(), leg[2], leg[3]);
                    tickN(sim, 30, true);
                }
                Point end = sim.p();
                boolean ok = Math.abs(end.x - leg[2]) <= 40 && Math.abs(end.y - leg[3]) <= 40;
                System.out.println("CHASE s" + stat + " (" + leg[0] + "," + leg[1] + ") -> ("
                        + leg[2] + "," + leg[3] + ") end=" + end.x + "," + end.y
                        + " " + (ok ? "OK" : "STUCK") + " nav=" + sim.st().lastNavDecision);
                if (!ok) {
                    System.out.println(sim.trace());
                }
                org.junit.jupiter.api.Assertions.assertTrue(ok,
                        "profile " + stat + ": chasing the shelf mob must LAND on it, ended at " + end);
            }
        }
    }
}
