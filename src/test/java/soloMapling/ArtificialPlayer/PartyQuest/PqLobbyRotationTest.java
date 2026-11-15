package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.Environment.EnvironmentPopulationConfig;
import soloMapling.companion.CompanionRoster;
import soloMapling.server.BotChannelRouter;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PqLobbyRotationTest {
    private static final long FIRST_TICK = 1_000L;
    private static final long INTERVAL = PqLobbyRotation.ROTATE_INTERVAL_MS;
    private final PqRecruitPoints.Point point = PqRecruitPoints.byName("LudiPQ");
    private final BotTypeManager.BotType type = BotTypeManager.BotType.LUDI_PQ_BOT;
    private MockedStatic<PqBotSpawner> spawner;
    private MockedStatic<BotGeneration> generation;
    private MockedStatic<BotChannelRouter> router;
    private MockedStatic<BotPartyQueue> queues;
    private BotPartyQueue queue;
    private Channel channel;

    @BeforeAll
    static void stubHost() throws Exception {
        PqLobbySpawnTest.stubHost();
    }

    @BeforeEach
    void setUp() {
        spawner = mockStatic(PqBotSpawner.class);
        spawner.when(() -> PqBotSpawner.recruitPaths(any())).thenCallRealMethod();
        spawner.when(() -> PqBotSpawner.botTypeFor(anyString())).thenCallRealMethod();
        spawner.when(() -> PqBotSpawner.recruitCount(any())).thenReturn(15);
        spawner.when(() -> PqBotSpawner.spawnRecruitBatch(any(), any(), any(), any()))
                .thenReturn(List.of(2_000));
        generation = mockStatic(BotGeneration.class);
        router = mockStatic(BotChannelRouter.class);
        router.when(() -> BotChannelRouter.channelOf(any())).thenReturn(1);
        queue = mock(BotPartyQueue.class);
        queues = mockStatic(BotPartyQueue.class);
        queues.when(BotPartyQueue::getInstance).thenReturn(queue);
        channel = mock(Channel.class);
        when(channel.getId()).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        PqLobbyRotation.stop();
        queues.close();
        router.close();
        generation.close();
        spawner.close();
    }

    @Test
    void rotatesStartupBotsBeforeNewArrivalsAndKeepsTheExistingCadence() {
        var lobby = lobby(point, type, channel);
        List<BotSM> bots = cohort(point);
        Character founder = bots.getFirst().getChr();
        lobby.tick(bots, FIRST_TICK);
        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(point, type, channel, List.of(110)));

        bots.add(recruit(point, 2_000, 110));
        lobby.tick(bots, FIRST_TICK + INTERVAL - 1);
        generation.verifyNoInteractions();
        lobby.tick(bots, FIRST_TICK + INTERVAL);
        generation.verify(() -> BotGeneration.removeBotFromServer(founder));
        generation.verifyNoMoreInteractions();

        bots.removeFirst();
        lobby.tick(bots, FIRST_TICK + 2 * INTERVAL);
        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(point, type, channel, List.of(120)));
    }

    @Test
    void skipsBusyOldestBotAndRetiresAnotherSurplusCareer() {
        var lobby = lobby(point, type, channel);
        List<BotSM> bots = cohort(point);
        lobby.tick(bots, FIRST_TICK);
        bots.add(recruit(point, 2_000, 110));
        bots.add(recruit(point, 2_001, 120));
        when(bots.getFirst().getChr().getParty()).thenReturn(mock(Party.class));

        lobby.tick(bots, FIRST_TICK + INTERVAL);

        generation.verify(() -> BotGeneration.removeBotFromServer(bots.get(1).getChr()));
        generation.verifyNoMoreInteractions();
    }

    @Test
    void pendingInvitationsAndPartiesDoNotCountTowardCoverage() {
        List<BotSM> bots = cohort(point);
        when(bots.getFirst().getChr().getParty()).thenReturn(mock(Party.class));
        when(queue.hasPendingInvite(bots.get(1).getChr())).thenReturn(true);

        lobby(point, type, channel).tick(bots, FIRST_TICK);

        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(
                point, type, channel, List.of(110, 120)));
        generation.verifyNoInteractions();
    }

    @Test
    void anotherChannelsBotCannotCoverAMissingCareer() {
        List<BotSM> bots = cohort(point);
        BotSM foreign = bots.getFirst();
        router.when(() -> BotChannelRouter.channelOf(foreign.getChr())).thenReturn(2);

        lobby(point, type, channel).tick(bots, FIRST_TICK);

        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(point, type, channel, List.of(110)));
    }

    @Test
    void neverRetiresTheLastRecruitableCopyEvenIfTheReplacementIsReserved() {
        var lobby = lobby(point, type, channel);
        List<BotSM> bots = cohort(point);
        lobby.tick(bots, FIRST_TICK);
        BotSM reserved = recruit(point, 2_000, 110);
        when(queue.hasPendingInvite(reserved.getChr())).thenReturn(true);
        bots.add(reserved);
        bots.add(recruit(point, 2_001, 230));

        lobby.tick(bots, FIRST_TICK + INTERVAL);

        generation.verify(() -> BotGeneration.removeBotFromServer(bots.get(5).getChr()));
        generation.verifyNoMoreInteractions();
    }

    @Test
    void recountsAvailabilityImmediatelyBeforeRetirement() {
        var lobby = lobby(point, type, channel);
        List<BotSM> bots = cohort(point);
        lobby.tick(bots, FIRST_TICK);
        BotSM replacement = recruit(point, 2_000, 110);
        bots.add(replacement);
        when(queue.hasPendingInvite(replacement.getChr())).thenReturn(false, true);

        lobby.tick(bots, FIRST_TICK + INTERVAL);

        generation.verifyNoInteractions();
    }

    @Test
    void failedAdditionDoesNotAuthoriseAnyRetirement() {
        spawner.when(() -> PqBotSpawner.spawnRecruitBatch(any(), any(), any(), any()))
                .thenReturn(List.of());
        var lobby = lobby(point, type, channel);
        List<BotSM> bots = cohort(point);

        lobby.tick(bots, FIRST_TICK);
        lobby.tick(bots, FIRST_TICK + INTERVAL);

        generation.verifyNoInteractions();
        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(
                point, type, channel, List.of(110)), times(2));
    }

    @Test
    void consumedReplacementDoesNotLeaveTheRemovePhaseWedged() {
        var lobby = lobby(point, type, channel);
        List<BotSM> bots = cohort(point);
        lobby.tick(bots, FIRST_TICK);
        // The added body left for a party; there is no spare recruit to remove.
        lobby.tick(bots, FIRST_TICK + INTERVAL);
        lobby.tick(bots, FIRST_TICK + 2 * INTERVAL);

        generation.verifyNoInteractions();
        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(
                point, type, channel, List.of(110)), times(2));
    }

    @Test
    void returningPartiesAreTrimmedWithoutAddingMoreSurplus() {
        List<BotSM> bots = cohort(point);
        for (int i = 0; i < 5; i++) {
            bots.add(recruit(point, 2_000 + i, 110));
        }

        lobby(point, type, channel).tick(bots, FIRST_TICK);

        generation.verify(() -> BotGeneration.removeBotFromServer(bots.getFirst().getChr()));
        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(any(), any(), any(), any()), never());
    }

    @Test
    void channelsAndDifferentQuestsHaveIndependentRotationPhases() {
        Channel second = mock(Channel.class);
        when(second.getId()).thenReturn(2);
        List<BotSM> secondCohort = cohort(point);
        for (BotSM bot : secondCohort) {
            router.when(() -> BotChannelRouter.channelOf(bot.getChr())).thenReturn(2);
        }
        var orbis = PqRecruitPoints.ORBIS;
        lobby(point, type, channel).tick(cohort(point), FIRST_TICK);
        lobby(point, type, second).tick(secondCohort, FIRST_TICK);
        lobby(orbis, BotTypeManager.BotType.OPQ_BOT, channel).tick(cohort(orbis), FIRST_TICK);

        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(point, type, channel, List.of(110)));
        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(point, type, second, List.of(110)));
        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(
                orbis, BotTypeManager.BotType.OPQ_BOT, channel, List.of(110)));
        generation.verifyNoInteractions();
    }

    @Test
    void anExpeditionMemberIsBusyEvenWithoutAParty() {
        List<BotSM> bots = cohort(point);
        PartyQuestBot member = (PartyQuestBot) bots.getFirst();
        when(member.inRunGroup(member.getChr())).thenReturn(true);

        lobby(point, type, channel).tick(bots, FIRST_TICK);

        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(point, type, channel, List.of(110)));
    }

    @Test
    void persistentCompanionsAreNeverTreatedAsDisposableLobbyBots() {
        List<BotSM> bots = cohort(point);
        int id = bots.getFirst().getChr().getId();
        CompanionRoster.register(id);
        try {
            lobby(point, type, channel).tick(bots, FIRST_TICK);
            spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(point, type, channel, List.of(110)));
            generation.verifyNoInteractions();
        } finally {
            CompanionRoster.unregister(id);
        }
    }

    @Test
    void tradingBotsAreNotAvailableRecruits() {
        List<BotSM> bots = cohort(point);
        when(bots.getFirst().getState()).thenReturn(BotSM.BotState.TRADING);

        lobby(point, type, channel).tick(bots, FIRST_TICK);

        spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(point, type, channel, List.of(110)));
    }

    @Test
    void startIsIdempotentAndUnloadAllowsRestartWithoutWaiting() {
        try (var executors = mockStatic(ExecutorServiceManager.class)) {
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            ScheduledFuture<?> timer = mock(ScheduledFuture.class);
            executors.when(ExecutorServiceManager::getScheduledExecutorService).thenReturn(scheduler);
            doReturn(timer).when(scheduler).scheduleWithFixedDelay(
                    any(Runnable.class), eq(10L), eq(10L), eq(TimeUnit.SECONDS));

            PqLobbyRotation.start();
            PqLobbyRotation.start();
            verify(scheduler).scheduleWithFixedDelay(
                    any(Runnable.class), eq(10L), eq(10L), eq(TimeUnit.SECONDS));
            PqLobbyRotation.stop();
            verify(timer).cancel(false);
            PqLobbyRotation.start();
            verify(scheduler, times(2)).scheduleWithFixedDelay(
                    any(Runnable.class), eq(10L), eq(10L), eq(TimeUnit.SECONDS));
        }
    }

    @Test
    void scheduledMaintenanceCoversAllLobbiesOnEveryChannelAndHonoursTheWaveSwitch() {
        try (var executors = mockStatic(ExecutorServiceManager.class);
             var servers = mockStatic(Server.class);
             var config = mockStatic(EnvironmentPopulationConfig.class);
             var game = mockStatic(GameConfig.class);
             var storage = mockStatic(CharacterStorage.class)) {
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            ScheduledFuture<?> timer = mock(ScheduledFuture.class);
            ExecutorService workers = mock(ExecutorService.class);
            Future<?> finished = mock(Future.class);
            when(finished.isDone()).thenReturn(true);
            doAnswer(call -> {
                ((Runnable) call.getArgument(0)).run();
                return finished;
            }).when(workers).submit(any(Runnable.class));
            executors.when(ExecutorServiceManager::getScheduledExecutorService).thenReturn(scheduler);
            executors.when(ExecutorServiceManager::getVirtualThreadExecutorService).thenReturn(workers);
            doReturn(timer).when(scheduler).scheduleWithFixedDelay(
                    any(Runnable.class), eq(10L), eq(10L), eq(TimeUnit.SECONDS));
            Server server = mock(Server.class);
            World world = mock(World.class);
            Channel second = mock(Channel.class);
            when(second.getId()).thenReturn(2);
            servers.when(Server::getInstance).thenReturn(server);
            when(server.getWorld(0)).thenReturn(world);
            when(world.getChannels()).thenReturn(List.of(channel, second));
            var plan = mock(EnvironmentPopulationConfig.PopulationPlan.class);
            var waves = mock(EnvironmentPopulationConfig.WaveLateArrivals.class);
            config.when(EnvironmentPopulationConfig::plan).thenReturn(plan);
            when(plan.lateArrivals()).thenReturn(waves);
            when(waves.enabled()).thenReturn(true);
            when(waves.opqLobby()).thenReturn(true);
            when(waves.pqLobbies()).thenReturn(true);
            game.when(() -> GameConfig.getServerBoolean("use_cpq")).thenReturn(true);
            storage.when(CharacterStorage::getAllBots).thenReturn(Map.of());

            PqLobbyRotation.start();
            ArgumentCaptor<Runnable> pulse = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleWithFixedDelay(
                    pulse.capture(), eq(10L), eq(10L), eq(TimeUnit.SECONDS));
            pulse.getValue().run();

            List<PqRecruitPoints.Point> points = new ArrayList<>(PqRecruitPoints.ALL);
            points.addAll(List.of(PqRecruitPoints.ORBIS, PqBotSpawner.CARNIVAL, PqBotSpawner.CARNIVAL_2));
            for (Channel ch : List.of(channel, second)) {
                for (var point : points) {
                    var type = PqBotSpawner.botTypeFor(point.name());
                    var paths = PqBotSpawner.recruitPaths(point);
                    spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(point, type, ch, paths));
                }
            }
            spawner.clearInvocations();
            when(waves.enabled()).thenReturn(false);
            pulse.getValue().run();
            spawner.verify(() -> PqBotSpawner.spawnRecruitBatch(any(), any(), any(), any()), never());
        }
    }

    private PqLobbyRotation.Lobby lobby(PqRecruitPoints.Point point, BotTypeManager.BotType type,
                                         Channel channel) {
        return new PqLobbyRotation.Lobby(point, type, channel, FIRST_TICK);
    }

    private List<BotSM> cohort(PqRecruitPoints.Point point) {
        List<BotSM> bots = new ArrayList<>();
        for (int path : PqBotSpawner.recruitPaths(point)) {
            bots.add(recruit(point, 1_000 + bots.size(), path));
        }
        return bots;
    }

    private BotSM recruit(PqRecruitPoints.Point point, int id, int path) {
        BotSM bot;
        if (point.equals(PqRecruitPoints.ORBIS)) {
            OPQBot opq = mock(OPQBot.class);
            when(opq.getOPQBotState()).thenReturn(OPQBot.OPQBotState.RECRUITMENT);
            bot = opq;
        } else {
            PartyQuestBot pq = mock(PartyQuestBot.class);
            when(pq.lobbyMapId()).thenReturn(point.recruitMap());
            bot = pq;
        }
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Job job = Job.getById(path);
        when(chr.getId()).thenReturn(id);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(point.recruitMap());
        when(chr.getLevel()).thenReturn(Math.max(30, point.minLevel()));
        when(chr.getJob()).thenReturn(job);
        when(chr.isAlive()).thenReturn(true);
        when(bot.getChr()).thenReturn(chr);
        when(bot.getRunning()).thenReturn(true);
        return bot;
    }
}
