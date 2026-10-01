package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 玩具塔 A 类梯（顶部无落脚台，实测 28/394 条）的顶部钳制释放：执行级 sim 驱动
 * 生产 driver tick，让 bot 抓住 221020100 x=-3 这条裸绳头梯并持续上爬 ——
 * 修复前 resolveClimbBoundary 找不到落脚点就永久钳制（挂在绳顶的报障形态）；
 * 修复后 TOP_CLAMP_RELEASE_MS 到点释放为 fall，bot 回到地面可再决策。
 */
class EosRopeTopClampReleaseTest {

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
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(), "WZ unavailable");
    }

    static boolean mapWzAvailable() {
        try {
            org.gms.provider.DataProvider mapSource =
                    org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return mapSource.getData("Map/Map2/221020100.img") != null;
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

    private record Sim(BotMovementState st, AtomicReference<Point> pos) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(int mapId, int spawnX, int spawnY, BotMovementProfile profile) {
        var map = BotNavigationMapLoader.loadMapGeometry(mapId);
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
        return new Sim(st, pos);
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

    /**
     * The bare-headed rope at 221020100 x=-3 (top 466, floor 788): the mob row at y=788
     * sits UNDER the rope head with no step-off, so the climb clamps at the top. The sim
     * keeps re-issuing the climb; the release must bring the bot DOWN off the rope within
     * the clamp window (2.5s = 50 ticks) plus the fall, i.e. it must not be climbing
     * still-clamped at the same pixel after 10s.
     */
    @Test
    void aBotClampedOnABareRopeHeadIsReleasedWithinTheClampWindow() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(221020100, -3, 788, profile);

        // Re-issue the up-climb every beat so the bot keeps trying (the grind loop shape).
        // The clamp window is wall-clock, and the sim spins far faster than real time, so
        // bound the loop by WALL-CLOCK (5s after the clamp starts) rather than a tick count:
        // under suite load the ticks-per-second varies, but the release must fire within
        // TOP_CLAMP_RELEASE_MS of wall time either way.
        int clampedTicks = 0;
        int maxClampStretch = 0;
        boolean sawClamp = false;
        boolean sawReleased = false;
        boolean clampedStillStuck = false;
        long clampStartWall = 0L;
        for (int beat = 0; beat < 60_000; beat++) {
            move(sim.st(), -3, 466);
            for (int i = 0; i < 5; i++) { // 250ms beat
                tickN(sim, 1, 221020100);
                if (sim.st().climbing && sim.st().topClampSinceMs != 0L) {
                    if (!sawClamp) {
                        sawClamp = true;
                        clampStartWall = System.currentTimeMillis();
                    }
                    clampedTicks++;
                    if (System.currentTimeMillis() - clampStartWall > 5_000) {
                        // Still clamped 5s in: the release never fired.
                        clampedStillStuck = true;
                        break;
                    }
                } else {
                    maxClampStretch = Math.max(maxClampStretch, clampedTicks);
                    clampedTicks = 0;
                    if (sawClamp) {
                        sawReleased = true; // clamp state dropped (released or landed)
                        break;
                    }
                }
            }
            if (sawReleased || clampedStillStuck) {
                break;
            }
        }
        maxClampStretch = Math.max(maxClampStretch, clampedTicks);

        System.out.println("clamp-release END " + sim.p() + " climbing=" + sim.st().climbing
                + " sawClamp=" + sawClamp + " sawReleased=" + sawReleased
                + " clampedStillStuck=" + clampedStillStuck
                + " maxClampStretchTicks=" + maxClampStretch);
        assertTrue(sawClamp, "the sim should have driven the bot onto the bare rope head");
        assertFalse(clampedStillStuck,
                "a bare-head clamp must release within TOP_CLAMP_RELEASE_MS");
        assertTrue(sawReleased,
                "the clamp state must drop (release/landing) once the window passes");
    }
}
