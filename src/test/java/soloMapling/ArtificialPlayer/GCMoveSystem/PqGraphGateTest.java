package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
 * The contract behind {@link GCMovement#ensureGraph}: once it answers true, the driver must never
 * run the warmup heuristic for that map, and the stage-1 shelf chain the heuristic cannot climb
 * (-450 → -506 → -539 → -572, whose x ranges all sit inside the -450 platform) must be walkable.
 *
 * <p>The pre-existing {@code LpqStage1ExecutionSimTest} bakes the graph in its fixture
 * ({@code BotNavigationGraphProvider.getGraph(...)}) while also forcing
 * {@code graphWarmupFallback = true}, so it exercises neither half of this contract: fallback was
 * never actually in charge, and no test asserted that it stops being in charge.
 */
public class PqGraphGateTest {

    private static final int STAGE_1 = 922010100;

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
        for (String bean : new String[]{"messageSource", "logSource", "exceptionSource"}) {
            when(ctx.getBean(org.mockito.ArgumentMatchers.eq(bean),
                    org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                    .thenReturn(messageSource);
        }
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        MovementClock.useVirtual(1_000_000L);
        Assumptions.assumeTrue(mapWzAvailable(), "Map.wz not resolvable from this working directory");
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    static boolean mapWzAvailable() {
        try {
            var src = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return src.getData("Map/Map9/" + STAGE_1 + ".img") != null;
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

    private record Sim(org.gms.client.Character bot, BotMovementState st, AtomicReference<Point> pos) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(MapleMap map) {
        AtomicReference<Point> pos = new AtomicReference<>(new Point(136, -450));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(STAGE_1);
        when(bot.getId()).thenReturn(4242);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        when(bot.getStance()).thenReturn(0);
        when(bot.getBuffedValue(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getInventory(org.mockito.ArgumentMatchers.any())).thenReturn(
                mock(org.gms.client.inventory.Inventory.class, RETURNS_DEEP_STUBS));
        when(bot.getParty()).thenReturn(null);
        org.mockito.Mockito.doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = BotMovementProfile.fromCharacter(bot);
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(136, -450));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        return new Sim(bot, st, pos);
    }

    /** The production dispatch, driven directly (airborne/climbing/grounded). */
    private static void tick(Sim sim, Point target) {
        BotMovementState st = sim.st();
        if (st.climbing) {
            BotMovementManager.tickClimbing(st, target, true);
        } else if (st.inAir) {
            BotMovementManager.tickAirborne(st, target);
        } else {
            BotMovementManager.tickGrounded(st, target);
        }
        MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
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

    @Test
    void ensureGraphPublishesTheExactProfile() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(STAGE_1);
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(STAGE_1);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);

        org.junit.jupiter.api.Assertions.assertTrue(GCMovement.ensureGraph(bot),
                "ensureGraph must produce a graph on the calling thread");
        org.junit.jupiter.api.Assertions.assertNotNull(
                BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.fromCharacter(bot)),
                "the exact-profile graph must be the one published, or every JUMP is withheld "
                        + "by tryExecuteJump's graph-identity gate");
        org.junit.jupiter.api.Assertions.assertTrue(GCMovement.hasGraph(bot),
                "hasGraph must report the graph ensureGraph just published");
        // Second call is a cache hit, not a re-bake: a rebuild would publish a NEW graph instance.
        BotNavigationGraph first = BotNavigationGraphProvider.peekGraph(
                map, BotMovementProfile.fromCharacter(bot));
        org.junit.jupiter.api.Assertions.assertTrue(GCMovement.ensureGraph(bot));
        org.junit.jupiter.api.Assertions.assertSame(first, BotNavigationGraphProvider.peekGraph(
                        map, BotMovementProfile.fromCharacter(bot)),
                "a cache hit must return the already-published graph, not rebuild it");
    }

    @Test
    void hasGraphIsFalseUntilTheGraphIsPublished() {
        // Synthetic id + geometry: a real map id may already be baked by another test in this JVM.
        MapleMap map = new MapleMap(922019981, 0, 0, 922010000, 0f);
        map.setMapLineBoundings(-4000, 4000, -4000, 4000);
        map.setFootholds(new org.gms.server.maps.FootholdTree(
                new Point(-4000, -4000), new Point(4000, 4000)));
        map.getFootholds().insert(new org.gms.server.maps.Foothold(
                new Point(-200, 0), new Point(200, 0), 1));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(922019981);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);

        org.junit.jupiter.api.Assertions.assertFalse(GCMovement.hasGraph(bot),
                "an unbaked map must report no graph");
        org.junit.jupiter.api.Assertions.assertTrue(GCMovement.ensureGraph(bot));
        org.junit.jupiter.api.Assertions.assertTrue(GCMovement.hasGraph(bot));
    }

    /**
     * With the graph gated in, the driver must climb the -450 → -572 rung chain: the stage-1 shelf
     * step that the warmup heuristic can never take (its jump probe only runs when the walk step is
     * blocked, and these rungs sit inside the platform's own x range).
     */
    @Test
    void driverClimbsTheShelfChainWithNoFallback() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(STAGE_1);
        Sim sim = sim(map);
        org.junit.jupiter.api.Assertions.assertTrue(
                GCMovement.ensureGraph(sim.bot()),
                "ensureGraph must publish the exact-profile graph for this bot");

        // Production re-issues the seek move every macro beat; mirror that (30 ticks ~ 1.5s).
        Point target = new Point(136, -572);
        for (int beat = 0; beat < 20; beat++) {
            sim.st().moveTarget = target;
            sim.st().moveTargetPrecise = true;
            sim.st().moveBestDist = Integer.MAX_VALUE;
            sim.st().moveProgressAtMs = MovementClock.nowMs();
            for (int i = 0; i < 30; i++) {
                if (i % 30 == 0) ObserverTracker.markObservedNow(STAGE_1);
                TICK.invoke(null, sim.st());
                MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            }
            if (Math.abs(sim.p().y - -572) <= 12) {
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(
                Math.abs(sim.p().x - 136) <= 30 && Math.abs(sim.p().y - -572) <= 30,
                "with the exact graph the bot must reach the -572 Ratz shelf, ended at " + sim.p());
        org.junit.jupiter.api.Assertions.assertFalse(sim.st().graphWarmupFallback,
                "once the graph is published the driver must never report fallback");
    }
}
