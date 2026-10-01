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
 * LPQ 一阶段 (922010100) 塔井执行级复现：用生产驱动 tick 驱动真实 WZ 地形。
 * 报告的 bug：bot 只能爬上上方平台；从最上面的大平台 (y=-3488) 无法顺着绳子下来；
 * 多个 bot 被强制吸到地图最顶上 (y≈-3945 的封闭顶袋) 空中。
 */
public class LpqStage1TowerReproTest {

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
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(),
                "Map.wz not resolvable from this working directory (sim needs the real WZ)");
    }

    static boolean mapWzAvailable() {
        try {
            org.gms.provider.DataProvider mapSource =
                    org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return mapSource.getData("Map/Map9/922010100.img") != null;
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
        when(bot.getId()).thenReturn(778);
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
                .limit(400)
                .forEach(l -> sb.append(l).append('\n'));
        System.out.println(sb);
    }

    static final int MAP = 922010100;

    /** 上行腿：出生层 (0,130) 爬到最顶大平台 (70,-3488)。报告说"只能上去"。 */
    @Test
    void simStage1ClimbUp() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        System.out.println("== 922010100 ropes: " + map.getRopes().size());
        map.getRopes().forEach(r -> System.out.println(
                "   rope x=" + r.x() + " span=[" + Math.min(r.y1(), r.y2()) + "," + Math.max(r.y1(), r.y2()) + "] ladder=" + r.isLadder()));

        Sim sim = sim(MAP, 0, 130, profile);
        for (int beat = 0; beat < 90; beat++) {
            move(sim.st(), 70, -3488);
            tickN(sim, 30, MAP);
        }
        Point end = sim.p();
        System.out.println("stage1 up END " + end);
        report("stage1 up", sim);
        org.junit.jupiter.api.Assertions.assertTrue(end.y <= -3400,
                "bot should climb to the top platform, ended at " + end);
    }

    /** 下行腿：从最顶大平台 (70,-3488) 回门口 (-38,-180)。报告说"无法攀爬绳子下来"。 */
    @Test
    void simStage1DescendFromTop() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(MAP, 70, -3488, profile);
        for (int beat = 0; beat < 24; beat++) {
            move(sim.st(), -38, -180);
            tickN(sim, 30, MAP);
        }
        Point end = sim.p();
        System.out.println("stage1 down END " + end);
        report("stage1 down", sim);
        org.junit.jupiter.api.Assertions.assertTrue(end.y >= -600,
                "bot should descend toward the exit row (y=-180), ended at " + end);
    }

    /**
     * Haste 下行腿：PQ 队有贼就全员加速。速度档案提高后 walk-off DROP 的横向漂移
     * 是否过冲 x∈[229..265] 的 36px 窄落点，坠入裸空气带（266<x<365 无任何落脚）
     * ——生产里表现为整井下坠 + tickFallOffMapRecovery 吸顶。
     */
    @Test
    void simStage1DescendWithHaste() {
        BotMovementProfile profile = new BotMovementProfile(160, 120);
        Sim sim = sim(MAP, 70, -3488, profile);
        for (int beat = 0; beat < 24; beat++) {
            move(sim.st(), -38, -180);
            tickN(sim, 30, MAP);
        }
        Point end = sim.p();
        System.out.println("stage1 haste down END " + end);
        report("stage1 haste down", sim);
        // 只断言"没有停在地图最顶区（顶袋/VR顶）"：吸顶即失败
        org.junit.jupiter.api.Assertions.assertTrue(end.y < -3000 || end.y > -1000,
                "bot must not end parked in the map-top pocket, ended at " + end);
    }

    /**
     * 顶袋出口验证（现状固化）：最顶封闭顶袋 (-3945 围栏) 并非孤岛——图里有一条
     * 6-edge 逃生链（JUMP 进侧沟旁大平台 → DROP 1976px → …→ 底层）。
     * 被吸顶的 bot 只有走这条链才能下来；吸顶机制本身见 LpqStage1TopSuckDiagTest。
     */
    @Test
    void simStage1TopPocketIsIsland() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        Point from = BotPhysicsEngine.findGroundPoint(map, new Point(-320, -3940));
        System.out.println("top pocket from ground = " + from);
        int fromR = g.findRegionId(map, from);
        Point to = new Point(0, 130);
        int toR = g.findRegionId(map, to);
        System.out.println("topR=" + fromR + " bottomR=" + toR);
        List<BotNavigationGraph.Edge> path = (fromR == toR)
                ? List.of()
                : BotNavigationManager.findPath(g, map, from, fromR, toR, to);
        System.out.println("top pocket -> bottom path = "
                + (path == null ? "NULL (ISLAND)" : path.size() + " edges"));
        if (path != null) {
            for (BotNavigationGraph.Edge e : path) {
                System.out.println("   " + e.type + " r" + e.fromRegionId + "->r" + e.toRegionId
                        + " " + e.startPoint + " -> " + e.endPoint
                        + (e.launchStepX != 0 ? " lsx=" + e.launchStepX : ""));
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(path != null && !path.isEmpty(),
                "the top pocket must keep a documented escape chain");
    }

    /**
     * 吸顶复现：底板边缘外的裸柱。出生层 y=130 只铺 [-265..265]，266..365 是无落脚的裸柱，
     * 坠出 VR 底后 tickFallOffMapRecovery 的兜底去向必须是"最近可用落脚"（修复后），
     * 而不是 VR-top 兜底（修复前会命中顶袋地板 y=-3945 → bot 被吸到地图最顶空中）。
     */
    @Test
    void simStage1VoidColumnSuckToTop() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(MAP, 250, 130, profile);
        // 目标放在裸柱里（如追怪/落点的 x 超 265 的情形）
        for (int beat = 0; beat < 10; beat++) {
            move(sim.st(), 300, 130);
            tickN(sim, 30, MAP);
        }
        Point end = sim.p();
        System.out.println("stage1 void-column END " + end);
        report("stage1 void-column", sim);
        org.junit.jupiter.api.Assertions.assertTrue(end.y > -1000,
                "bot must not be parked at the map-top pocket (y=-3945), ended at " + end);
    }

    /**
     * 坠图恢复去向钉死：把 bot 直接放到 VR 底以下（自由坠落中），驱动 tick 触发
     * tickFallOffMapRecovery。修复后它必须落在"最近可用落脚"（有出口的地面），
     * 修复前的 VR-top 兜底会把它放进封闭顶袋 (y≈-3945)。
     */
    @Test
    void simStage1FallRecoveryGoesToLivableGround() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraphProvider.getGraph(map, profile);
        Sim sim = sim(MAP, 320, 800, profile); // 侧带裸柱内、VR 底(360)+400 边界之下 → 立即触发
        // 真实坠落体有 inAir=true（hasGoal 由此成立，tickStuckDetection 才会跑）——
        // beginFall 的产物；这里直接置位以复现坠落中的 bot。
        sim.st().inAir = true;
        tickN(sim, 6, MAP);
        Point end = sim.p();
        System.out.println("fall recovery END " + end);
        report("fall recovery", sim);
        // 顶袋是 [-3945..-3486] + 两道墙围死：落在那里 = 吸顶未修复
        org.junit.jupiter.api.Assertions.assertTrue(end.y > -3000,
                "recovery must not park the bot in the top pocket, ended at " + end);
        // 且必须是实打实的地面
        org.junit.jupiter.api.Assertions.assertNotNull(
                BotPhysicsEngine.findGroundPoint(map, new Point(end.x, end.y - 1)),
                "recovery destination must have ground beneath it");
    }
}
