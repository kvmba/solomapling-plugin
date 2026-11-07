package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 嘉年华准备地图 (980000100) 出界复现。
 *
 * <p>报告：bot 从中层平台向右跳，弧线走到最右端（x≈495，可站立边界）后被竖直截断，
 * 然后沿着边界直落，穿过地板 y=132 继续下坠，掉出地图。
 *
 * <p>嫌疑：{@code BotPhysicsEngine.mapSideBoundaryCollision} 只钳 X，返回
 * {@code WALL}，而 {@code collideWithAirWall} 把 X 拉回边界后仍保留原来的 Y 与下落速度。
 * 于是弧线在边界处变成"贴着墙直落"——若边界列在地板行之外（本图地板右端正好是 495，
 * 但边界钳制命中的 Y 在地板之下），就没有任何垫脚石，垂直落到地图外。
 */
public class CarnivalPrepJumpOutOfMapReproTest {

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
        when(messageSource.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
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
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(),
                "Map.wz 不可用（需要真实 WZ 才能跑这个复现）");
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    static boolean mapWzAvailable() {
        try {
            var src = org.gms.provider.DataProviderFactory.getDataProvider(
                    org.gms.provider.wz.WZFiles.MAP);
            return src.getData("Map/Map9/980000100.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t);
        d.setConfigSubType("0");
        d.setConfigCode(k);
        d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private static final int MAP = 980000100;

    private record Sim(BotMovementState st, AtomicReference<Point> pos, StringBuilder trace) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(int spawnX, int spawnY, BotMovementProfile profile) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraphProvider.getGraph(map, profile);

        AtomicReference<Point> pos = new AtomicReference<>(new Point(spawnX, spawnY));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP);
        when(bot.getId()).thenReturn(981);
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
        st.lastMap = map;
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

    private static void tickN(Sim sim, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (i % 30 == 0) {
                ObserverTracker.markObservedNow(MAP);
            }
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            BotMovementState st = sim.st();
            if (i % 1 == 0) {
                sim.trace().append(String.format(
                        "t=%4d pos=%s air=%b vY=%.1f ax=%.1f nav=%s mt=%s%n",
                        i, sim.p(), st.inAir, (double) st.velY, (double) st.airVelX,
                        st.lastNavDecision, st.moveTarget));
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
        st.moveProgressAtMs = MovementClock.nowMs();
    }

    private void dump(String label, Sim sim) {
        StringBuilder sb = new StringBuilder("=== " + label + "  end=" + sim.p() + "\n");
        for (String line : sim.trace().toString().split("\n")) {
            sb.append(line).append('\n');
        }
        System.out.println(sb);
    }

    /**
     * 从 y=-48 的中层平台右端 (x≈440) 向右跳，落点指向地图右侧。
     * 期望：落在某块真实平台上；实际（报告）：冲出 x=495 后贴边界直落出界。
     */
    @Test
    void aJumpTowardTheRightEdgeMustNotFallOutOfTheMap() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(430, -48, profile);

        // 先走一步让它站稳，再向右跳
        move(sim.st(), 470, -48);
        tickN(sim, 20);
        GCMovement.jumpToward(sim.st().bot, 1);
        tickN(sim, 200);

        Point end = sim.p();
        dump("right-edge jump", sim);

        int standableMaxX = 495;
        org.junit.jupiter.api.Assertions.assertTrue(end.x <= standableMaxX,
                "bot 越过了可站立右边界又没落地: " + end);
        org.junit.jupiter.api.Assertions.assertTrue(end.y <= 165,
                "bot 掉到地板行以下（出界）: " + end);
    }
}
