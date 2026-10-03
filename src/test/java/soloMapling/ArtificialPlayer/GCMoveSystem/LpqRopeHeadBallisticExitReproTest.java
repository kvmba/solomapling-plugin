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
 * REGRESSION (the original "追着上方怪小步卡顿走路, 左右移动, 不继续上去打怪" report, mechanism
 * pinned by a per-tick trace on the real 922010100 geometry): the graph mints BALLISTIC
 * straight-up rope exits (launchStepX==0, startPoint at firstClimbableY, e.g. r63->r42 at
 * x=-123) that the executor never fired — the old launchStepX==0 branch delegated to physics,
 * and the physics top-boundary probe on the LPQ tower resolves back onto the platform the bot
 * grabbed the rope FROM (r63's probe lands r44, y=-1399, while the edge targets r42). The bot
 * climbed to the rope head, physics dropped it back on r44, the committed edge was discarded,
 * and the same seeded A* re-planned the same path: climb -> drop back -> replan, forever. From
 * the viewer this is the "paces under the mob above, small stutter steps, never climbs up".
 *
 * <p>This sim drives the production driver tick at the PQ cadence (move re-issued every 30 ticks)
 * along the exact failing leg: from the fh332 kill shelf (179,-968) chasing the fh354 mob's floor
 * (-205,-1499) via the x=-123 rope. Before the fix the bot never left the (-123,-1399..-1396)
 * loop; after it, it lands on r42 and jumps on to r36.
 */
public class LpqRopeHeadBallisticExitReproTest {

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
        Point p() { return pos.get(); }
    }

    private static Sim sim(BotMovementProfile profile, int sx, int sy) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraphProvider.getGraph(map, profile);

        AtomicReference<Point> pos = new AtomicReference<>(new Point(sx, sy));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(922010100);
        when(bot.getId()).thenReturn(782);
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
        BotPhysicsEngine.teleportTo(st, bot, new Point(sx, sy));
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

    private static void tickN(Sim sim, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (i % 30 == 0) ObserverTracker.markObservedNow(922010100);
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            BotMovementState st = sim.st();
            if (i % 10 == 0) {
                sim.trace().append(String.format("t=%4d pos=%s nav=%-16s edge=%s climb=%b air=%b%n",
                        i, sim.p(), st.lastNavDecision,
                        st.navEdge == null ? "-" : st.navEdge.type + "@" + st.navEdge.fromRegionId
                                + "->" + st.navEdge.toRegionId + " sx=" + st.navEdge.launchStepX,
                        st.climbing, st.inAir));
            }
        }
    }

    private static void move(Sim sim, int x, int y) {
        BotMovementState st = sim.st();
        st.following = false;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
        st.moveTarget = new Point(x, y);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = System.currentTimeMillis();
    }

    /**
     * The reported shape, end to end: the bot stands on the fh332 shelf where it just killed its
     * mob and the chase re-targets the mob on fh354 above-left. On the way it must take the
     * ballistic rope-head exit at x=-123 and land on r42 (y=-1433). Before the fix it looped
     * (climb to -1396, physics drop back to -1399) and never crossed -1400.
     */
    @Test
    void chaseAboveCrossesTheRopeHeadInsteadOfLooping() {
        for (int[] stat : new int[][]{{105, 110}, {120, 115}, {100, 100}}) {
            BotMovementProfile profile = new BotMovementProfile(stat[0], stat[1]);
            Sim sim = sim(profile, 179, -968);
            Point target = BotPhysicsEngine.findGroundPoint(sim.st().bot.getMap(), new Point(-205, -1513));
            for (int beat = 0; beat < 40; beat++) {
                move(sim, target.x, target.y);
                tickN(sim, 30);
            }
            Point end = sim.p();
            boolean crossed = end.y <= -1450;
            if (!crossed) {
                System.out.println(sim.trace());
            }
            org.junit.jupiter.api.Assertions.assertTrue(crossed,
                    "profile " + stat[0] + "/" + stat[1]
                            + ": the chase above must cross the rope head, ended at " + end);
        }
    }
}
