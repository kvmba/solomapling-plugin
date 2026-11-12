package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

import java.awt.Point;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LpqStage3PickupExecutionTest {

    @TempDir
    static Path cacheDir;
    private static Path previousCacheDir;
    private static MapleMap map;
    private static Method tick;

    @BeforeAll
    static void bootHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, MessageSource.class)).thenReturn(messages);
        }
        Method setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
        GameConfigDO interval = new GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
        previousCacheDir = BotNavigationGraphProvider.cacheDir();
        BotNavigationGraphProvider.setCacheDir(cacheDir);
        map = BotNavigationMapLoader.loadMapGeometry(922010300);
        tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
    }

    @AfterAll
    static void restore() {
        MovementClock.reset();
        BotNavigationGraphProvider.setCacheDir(previousCacheDir);
    }

    @ParameterizedTest
    @CsvSource({
            "100, 100", "110, 100", "120, 110", "140, 120", "160, 123"
    })
    void thePickupWalkReachesEveryStairWithoutSwaying(int speed, int jump) throws Exception {
        BotMovementProfile profile = new BotMovementProfile(speed, jump);
        BotNavigationGraphProvider.rebuildGraph(map, profile);
        int[][] starts = {{-5, -1234}, {12, -1239}, {21, -1247}, {24, -1247}, {50, -1247}};
        int[][] drops = {{-127, -1168}, {-80, -1190}, {-54, -1212}, {-26, -1234}};
        for (int[] start : starts) {
            for (int[] drop : drops) {
                drive(profile, new Point(start[0], start[1]), new Point(drop[0], drop[1]));
            }
        }
    }

    private static void drive(BotMovementProfile profile, Point start, Point target) throws Exception {
        MovementClock.useVirtual(1_000_000L);
        AtomicReference<Point> position = new AtomicReference<>(start);
        CharacterDouble bot = new CharacterDouble(930300, map, position::get,
                point -> position.set(new Point(point)));
        BotMovementState state = new BotMovementState(bot, null);
        state.movementProfile = profile;
        state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        state.lastMap = map;
        state.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(state, bot, start);
        BotMovementManager.resetEntryStateAfterTeleport(state);
        state.moveTarget = new Point(target);
        state.moveTargetMap = map;
        state.moveTargetPrecise = true;
        state.moveBestDist = Integer.MAX_VALUE;
        state.moveProgressAtMs = MovementClock.nowMs();

        boolean neededRecovery = false;
        for (int i = 0; i < 200 && state.moveTarget != null; i++) {
            if (i % 20 == 0) ObserverTracker.markObservedNow(map.getId());
            tick.invoke(null, state);
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            neededRecovery |= state.unstuckCooldownMs > 0;
        }
        String scenario = profile + ": " + start + " -> " + target + ", ended at " + position.get();
        assertNull(state.moveTarget, scenario);
        assertFalse(state.inAir || state.climbing, scenario);
        assertTrue(position.get().distanceSq(target) <= 12 * 12, scenario);
        assertFalse(neededRecovery, scenario + ", stuck recovery was needed");
    }
}
