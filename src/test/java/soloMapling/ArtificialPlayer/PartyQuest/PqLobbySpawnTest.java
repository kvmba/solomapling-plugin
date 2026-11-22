package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.property.ServiceProperty;
import org.gms.server.life.NPC;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorate;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotGrindSystem.BotSpotPicker;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.Carnival.CarnivalPqData;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.Environment.PlatformPlacement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PqLobbySpawnTest {
    private record Spawn(Point position, int family, int low, int high, int job, int channel) {
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
        NPC tory = mock(NPC.class);
        when(tory.getPosition()).thenReturn(new Point(1434, 352));
        when(map.getNPCById(1012112)).thenReturn(tory);
        NPC lakelis = mock(NPC.class);
        when(lakelis.getPosition()).thenReturn(new Point(-540, 421));
        when(map.getNPCById(9020000)).thenReturn(lakelis);
        when(channel.getMapFactory().getMap(anyInt())).thenReturn(map);
        generation = mockStatic(BotGeneration.class);
        generation.when(() -> BotGeneration.createBotOnChannel(
                any(Point.class), eq(map), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    created.add(new Spawn(call.getArgument(0), call.getArgument(2), call.getArgument(3),
                            call.getArgument(4), call.getArgument(5), call.getArgument(6)));
                    return 1_000 + created.size();
                });
        types = mockStatic(BotTypeManager.class);
        spots = mockStatic(BotSpotPicker.class);
        spots.when(() -> BotSpotPicker.pickGroundSpots(eq(map), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(new Point(12, 34)));
        spots.when(() -> BotSpotPicker.pickGroundSpotsInRadius(
                        eq(map), anyInt(), anyInt(), any(Point.class), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    Point center = call.getArgument(3);
                    return List.of(new Point(center.x + 60, center.y));
                });
    }

    @AfterEach
    void tearDown() {
        spots.close();
        types.close();
        generation.close();
    }

    @Test
    void everyLobbyCreatesEveryCareerAtAnInteriorLevelOnTheRequestedChannel() {
        List<PqRecruitPoints.Point> points = new ArrayList<>(PqRecruitPoints.ALL);
        points.add(PqRecruitPoints.ORBIS);
        points.add(PqBotSpawner.CARNIVAL);
        points.add(PqBotSpawner.CARNIVAL_2);
        for (PqRecruitPoints.Point point : points) {
            created.clear();
            when(map.getId()).thenReturn(point.recruitMap());
            var paths = PqBotSpawner.recruitPaths(point);
            var type = PqBotSpawner.botTypeFor(point.name());
            assertNotNull(type, point.name());
            int low = PqBotSpawner.minRecruitLevel(point);
            int high = PqBotSpawner.maxRecruitLevel(point);
            assertTrue(point.minLevel() < low && low < high && high < point.maxLevel(),
                    () -> point.name() + ": " + low + "-" + high);

            List<Integer> ids = PqBotSpawner.spawnRecruitBatch(point, type, channel, paths);

            assertEquals(paths.size(), ids.size(), point.name());
            var observedPaths = new HashSet<Integer>();
            for (Spawn spawn : created) {
                observedPaths.add(spawn.job() / 10 * 10);
                assertTrue(spawn.low() > point.minLevel() && spawn.low() < point.maxLevel(),
                        point.name() + ": " + spawn);
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
    void townReplenishmentStaysByTheNpcEvenWhenTheGraphHasNoSpots() {
        spots.when(() -> BotSpotPicker.pickGroundSpotsInRadius(
                        eq(map), anyInt(), anyInt(), any(Point.class), anyInt(), anyInt()))
                .thenReturn(List.of());
        for (String name : List.of("HenesysPQ", "KerningPQ")) {
            created.clear();
            var point = PqRecruitPoints.byName(name);
            when(map.getId()).thenReturn(point.recruitMap());
            var area = PqRecruitArea.forMap(map);

            PqBotSpawner.spawnRecruitBatch(point, PqBotSpawner.botTypeFor(name), channel,
                    List.of(PqBotSpawner.recruitPaths(point).getFirst()));

            assertEquals(1, created.size());
            assertEquals(area.center(), created.getFirst().position());
        }
        spots.verify(() -> BotSpotPicker.pickGroundSpots(
                eq(map), anyInt(), anyInt(), anyInt()), never());
    }

    @Test
    void levelThirtyCapsOfferAllInteriorLevelsAndOnlyFirstJobs() {
        var kerning = PqRecruitPoints.byName("KerningPQ");
        var ariant = PqRecruitPoints.byName("AriantPQ");
        assertEquals(22, PqBotSpawner.minRecruitLevel(kerning));
        assertEquals(21, PqBotSpawner.minRecruitLevel(ariant));
        for (var point : List.of(kerning, ariant)) {
            assertEquals(29, PqBotSpawner.maxRecruitLevel(point));
            assertEquals(List.of(100, 200, 300, 400, 500), PqBotSpawner.recruitPaths(point));
        }
    }

    @Test
    void carnivalFieldRecruitsUseTheirEditionsInteriorBand() {
        try (var placement = mockStatic(PlatformPlacement.class);
             var characters = mockStatic(BotHelpers.class);
             var decoration = mockStatic(BotDecorate.class)) {
            List<Integer> ids = List.of(1_001);
            for (boolean cpq2 : new boolean[]{false, true}) {
                var point = cpq2 ? PqBotSpawner.CARNIVAL_2 : PqBotSpawner.CARNIVAL;
                int field = cpq2 ? CarnivalPqData.lobbyForField2(0) : CarnivalPqData.lobbyForField(0);
                Character bot = mock(Character.class);
                placement.when(() -> PlatformPlacement.spawnBotsOnMap(1, field, 2)).thenReturn(ids);
                characters.when(() -> BotHelpers.getCharFromChannelStorage(1_001)).thenReturn(bot);

                assertEquals(ids, PqBotSpawner.spawnCarnivalTeam(1, field, cpq2, 2));

                var level = ArgumentCaptor.forClass(Integer.class);
                verify(bot).setLevel(level.capture());
                assertTrue(point.minLevel() < level.getValue() && level.getValue() < point.maxLevel());
                decoration.verify(() -> BotDecorate.alignCareerToLevel(bot));
            }
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
