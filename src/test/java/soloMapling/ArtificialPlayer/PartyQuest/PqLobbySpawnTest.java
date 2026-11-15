package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotGrindSystem.BotSpotPicker;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.Environment.EnvironmentManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PqLobbySpawnTest {
    private record Spawn(int family, int low, int high, int job, int channel) {
    }

    private final List<Spawn> created = new ArrayList<>();
    private MockedStatic<BotGeneration> generation;
    private MockedStatic<BotTypeManager> types;
    private MockedStatic<BotSpotPicker> spots;
    private Channel channel;
    private MapleMap map;

    @BeforeAll
    static void stubHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class);
        ConfigService configs = mock(ConfigService.class);
        when(configs.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(configs);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(call -> call.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, MessageSource.class)).thenReturn(messages);
        }
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
        GameConfigDO interval = new GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        Server.getInstance();
    }

    @BeforeEach
    void setUp() {
        channel = mock(Channel.class, RETURNS_DEEP_STUBS);
        when(channel.getId()).thenReturn(2);
        map = mock(MapleMap.class);
        when(channel.getMapFactory().getMap(anyInt())).thenReturn(map);
        generation = mockStatic(BotGeneration.class);
        generation.when(() -> BotGeneration.createBotOnChannel(
                any(Point.class), eq(map), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    created.add(new Spawn(call.getArgument(2), call.getArgument(3),
                            call.getArgument(4), call.getArgument(5), call.getArgument(6)));
                    return 1_000 + created.size();
                });
        types = mockStatic(BotTypeManager.class);
        spots = mockStatic(BotSpotPicker.class);
        spots.when(() -> BotSpotPicker.pickGroundSpots(eq(map), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(new Point(12, 34)));
    }

    @AfterEach
    void tearDown() {
        spots.close();
        types.close();
        generation.close();
    }

    @Test
    void everyLobbyCreatesEveryCareerAtAnEligibleLevelOnTheRequestedChannel() {
        List<PqRecruitPoints.Point> points = new ArrayList<>(PqRecruitPoints.ALL);
        points.add(PqRecruitPoints.ORBIS);
        points.add(PqBotSpawner.CARNIVAL);
        points.add(PqBotSpawner.CARNIVAL_2);
        for (PqRecruitPoints.Point point : points) {
            created.clear();
            var paths = PqBotSpawner.recruitPaths(point);
            var type = PqBotSpawner.botTypeFor(point.name());
            assertNotNull(type, point.name());

            List<Integer> ids = PqBotSpawner.spawnRecruitBatch(point, type, channel, paths);

            assertEquals(paths.size(), ids.size(), point.name());
            var observedPaths = new HashSet<Integer>();
            for (Spawn spawn : created) {
                observedPaths.add(spawn.job() / 10 * 10);
                assertTrue(point.levelEligible(spawn.low()), point.name() + ": " + spawn);
                assertEquals(spawn.low(), spawn.high());
                assertEquals(spawn.job() / 100, spawn.family());
                assertEquals(2, spawn.channel());
                int advancement = spawn.low() >= 120 ? 2 : spawn.low() >= 70 ? 1 : 0;
                assertEquals(advancement, spawn.job() % 10, point.name() + ": " + spawn);
            }
            assertEquals(new HashSet<>(paths), observedPaths, point.name());
        }
    }

    @Test
    void lowLevelLobbyUsesOnlyTheFiveFirstJobs() {
        var point = new PqRecruitPoints.Point("low", 100, 10, 29, 2, 4);
        var paths = PqBotSpawner.recruitPaths(point);
        assertEquals(List.of(100, 200, 300, 400, 500), paths);

        PqBotSpawner.spawnRecruitBatch(
                point, BotTypeManager.BotType.HENESYS_PQ_BOT, channel, paths);

        assertEquals(5, created.size());
        for (Spawn spawn : created) {
            assertTrue(spawn.low() >= 10 && spawn.low() < 30);
            assertEquals(0, spawn.job() % 100);
        }
    }

    @Test
    void startupSeedsEveryChannelAndScalingCannotRemoveCareerCoverage() {
        try (var servers = mockStatic(Server.class);
             var environment = mockStatic(EnvironmentManager.class)) {
            Server server = mock(Server.class);
            World world = mock(World.class);
            Channel first = mock(Channel.class, RETURNS_DEEP_STUBS);
            when(first.getId()).thenReturn(1);
            when(first.getMapFactory().getMap(anyInt())).thenReturn(map);
            servers.when(Server::getInstance).thenReturn(server);
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannels()).thenReturn(List.of(first, channel));
            when(world.getChannel(2)).thenReturn(channel);
            environment.when(() -> EnvironmentManager.scaledAmbient(anyInt())).thenReturn(1);
            var point = PqRecruitPoints.byName("MagatiaPQ");
            assertEquals(12, PqBotSpawner.recruitCount(point));

            assertEquals(24, PqBotSpawner.spawnFor(
                    point, BotTypeManager.BotType.MAGATIA_PQ_BOT).size());

            for (int ch : List.of(1, 2)) {
                var paths = new HashSet<Integer>();
                for (Spawn spawn : created) {
                    if (spawn.channel() == ch) {
                        paths.add(spawn.job() / 10 * 10);
                    }
                }
                assertEquals(new HashSet<>(PqBotSpawner.recruitPaths(point)), paths);
            }

            created.clear();
            assertEquals(12, PqBotSpawner.spawnCarnivalTeam(
                    12, PqBotSpawner.CARNIVAL_2.recruitMap(), true, 2).size());
            var carnivalPaths = new HashSet<Integer>();
            for (Spawn spawn : created) {
                carnivalPaths.add(spawn.job() / 10 * 10);
            }
            assertEquals(new HashSet<>(PqBotSpawner.recruitPaths(PqBotSpawner.CARNIVAL_2)),
                    carnivalPaths);
        }
    }

    @Test
    void missingMapDoesNotAttemptCharacterCreation() {
        when(channel.getMapFactory().getMap(anyInt())).thenReturn(null);
        var point = PqRecruitPoints.ORBIS;

        assertTrue(PqBotSpawner.spawnRecruitBatch(point, BotTypeManager.BotType.OPQ_BOT,
                channel, PqBotSpawner.recruitPaths(point)).isEmpty());

        generation.verifyNoInteractions();
        types.verifyNoInteractions();
    }

    @Test
    void rejectedSpawnIsNotReportedAsStarted() {
        generation.when(() -> BotGeneration.createBotOnChannel(
                any(Point.class), eq(map), anyInt(), anyInt(), anyInt(), anyInt(), eq(2)))
                .thenReturn(-1);

        assertTrue(PqBotSpawner.spawnRecruitBatch(PqRecruitPoints.ORBIS,
                BotTypeManager.BotType.OPQ_BOT, channel, List.of(230)).isEmpty());

        types.verifyNoInteractions();
    }
}
