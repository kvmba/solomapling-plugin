package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.BotLogger;
import soloMapling.Environment.EnvironmentPopulationConfig;
import soloMapling.companion.CompanionRoster;
import soloMapling.server.BotChannelRouter;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Maintains recruitable career coverage and rotates each enabled PQ lobby independently. */
public final class PqLobbyRotation {
    static final long ROTATE_INTERVAL_MS = 150_000L;
    private static final long COVERAGE_INTERVAL_SECONDS = 10L;
    private static PqLobbyRotation instance;

    private record LobbyKey(int map, int channel) {
    }

    private final Map<LobbyKey, Lobby> lobbies = new HashMap<>();
    private ScheduledFuture<?> timer;
    private Future<?> worker;
    private boolean stopped;

    private PqLobbyRotation() {
    }

    public static synchronized void start() {
        if (instance != null) {
            return;
        }
        instance = new PqLobbyRotation();
        instance.timer = ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(
                instance::scheduleTick, COVERAGE_INTERVAL_SECONDS, COVERAGE_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.cancel();
            instance = null;
        }
    }

    private synchronized void cancel() {
        stopped = true;
        timer.cancel(false);
        if (worker != null) {
            worker.cancel(true);
        }
    }

    private synchronized void scheduleTick() {
        if (!stopped && (worker == null || worker.isDone())) {
            // Character creation can wait for DB/spawn permits; never occupy a shared timer thread.
            worker = ExecutorServiceManager.getVirtualThreadExecutorService().submit(() -> {
                try {
                    tick();
                } catch (Exception e) {
                    BotLogger.log("PQ lobby maintenance failed: " + e);
                }
            });
        }
    }

    private void tick() {
        var world = Server.getInstance().getWorld(SoloMaplingConstants.GameConstants.WORLD_SCANIA);
        if (world == null) {
            return;
        }
        var waves = EnvironmentPopulationConfig.plan().lateArrivals();
        if (!waves.enabled()) {
            return;
        }
        List<PqRecruitPoints.Point> points = new ArrayList<>();
        if (waves.pqLobbies()) {
            points.addAll(PqRecruitPoints.ALL);
        }
        if (waves.opqLobby()) {
            points.add(PqRecruitPoints.ORBIS);
        }
        if (GameConfig.getServerBoolean("use_cpq")) {
            points.add(PqBotSpawner.CARNIVAL);
            points.add(PqBotSpawner.CARNIVAL_2);
        }
        Map<LobbyKey, List<BotSM>> occupants = new HashMap<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            if (!(bot instanceof PartyQuestBot) && !(bot instanceof OPQBot)) {
                continue;
            }
            Character chr = bot.getChr();
            if (chr != null && chr.getMap() != null) {
                LobbyKey key = new LobbyKey(chr.getMapId(), BotChannelRouter.channelOf(chr));
                occupants.computeIfAbsent(key, ignored -> new ArrayList<>()).add(bot);
            }
        }
        for (Channel channel : world.getChannels()) {
            for (PqRecruitPoints.Point point : points) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                var type = PqBotSpawner.botTypeFor(point.name());
                if (type == null) {
                    continue;
                }
                LobbyKey key = new LobbyKey(point.recruitMap(), channel.getId());
                long now = System.currentTimeMillis();
                Lobby lobby = lobbies.computeIfAbsent(key, ignored -> new Lobby(point, type, channel,
                        now + ROTATE_INTERVAL_MS / 2 + ThreadLocalRandom.current().nextLong(60_000L)));
                try {
                    lobby.tick(occupants.getOrDefault(key, List.of()), now);
                } catch (Exception e) {
                    // Map/DB failures belong to this lobby, not every other channel's maintenance.
                    BotLogger.log("PQ lobby maintenance failed: " + point.name()
                            + " ch" + channel.getId() + ": " + e);
                }
            }
        }
    }

    static final class Lobby {
        private final PqRecruitPoints.Point point;
        private final BotTypeManager.BotType type;
        private final Channel channel;
        private final List<Integer> paths;
        private long nextRotationAt;
        private boolean retireNext;

        Lobby(PqRecruitPoints.Point point, BotTypeManager.BotType type, Channel channel,
              long nextRotationAt) {
            this.point = point;
            this.type = type;
            this.channel = channel;
            this.paths = PqBotSpawner.recruitPaths(point);
            this.nextRotationAt = nextRotationAt;
        }

        void tick(List<BotSM> occupants, long now) {
            List<BotSM> idle = new ArrayList<>();
            int[] counts = new int[paths.size()];
            for (BotSM bot : occupants) {
                if (available(bot)) {
                    idle.add(bot);
                    int path = pathIndex(bot);
                    if (path >= 0) {
                        counts[path]++;
                    }
                }
            }
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == 0) {
                    missing.add(paths.get(i));
                }
            }
            if (!missing.isEmpty()) {
                PqBotSpawner.spawnRecruitBatch(point, type, channel, missing);
                return;
            }
            if (now < nextRotationAt) {
                return;
            }
            if (retireNext || idle.size() > PqBotSpawner.recruitCount(point)) {
                BotSM candidate = oldest(idle, counts, true);
                if (candidate != null) {
                    // Recount after selection: a player may have reserved the other copy meanwhile.
                    int path = pathIndex(candidate);
                    int copies = 0;
                    for (BotSM bot : idle) {
                        if (available(bot) && pathIndex(bot) == path) {
                            copies++;
                        }
                    }
                    if (available(candidate) && (path < 0 || copies > 1)) {
                        BotGeneration.removeBotFromServer(candidate.getChr());
                    } else {
                        return;
                    }
                }
                // An added recruit may itself have joined a party. Don't wedge the remove phase
                // when no surplus remains; the next add will duplicate another available career.
                retireNext = false;
            } else {
                BotSM candidate = oldest(idle, counts, false);
                List<Integer> created = PqBotSpawner.spawnRecruitBatch(
                        point, type, channel, List.of(paths.get(pathIndex(candidate))));
                if (created.isEmpty()) {
                    return; // failed additions must never authorise a retirement
                }
                retireNext = true;
            }
            nextRotationAt = now + ROTATE_INTERVAL_MS;
        }

        private int pathIndex(BotSM bot) {
            return paths.indexOf(bot.getChr().getJob().getId() / 10 * 10);
        }

        private BotSM oldest(List<BotSM> idle, int[] counts, boolean retiring) {
            BotSM oldest = null;
            for (BotSM bot : idle) {
                int path = pathIndex(bot);
                if (retiring ? path >= 0 && counts[path] <= 1 : path < 0) {
                    continue;
                }
                // Synthetic bot ids increase at creation. This includes startup bots immediately,
                // without a separate enrollment map that can accidentally track only new arrivals.
                if (oldest == null || bot.getChr().getId() < oldest.getChr().getId()) {
                    oldest = bot;
                }
            }
            return oldest;
        }

        private boolean available(BotSM bot) {
            Character chr = bot.getChr();
            BotSM.BotState state = bot.getState();
            if (!bot.getRunning() || chr == null || chr.getMap() == null || !chr.isAlive()
                    || CompanionRoster.isCompanion(chr.getId())
                    || state == BotSM.BotState.TRADING || state == BotSM.BotState.PAUSE
                    || state == BotSM.BotState.FINISHED
                    || chr.getMapId() != point.recruitMap()
                    || BotChannelRouter.channelOf(chr) != channel.getId()
                    || !point.levelEligible(chr.getLevel()) || chr.getParty() != null
                    || BotPartyQueue.getInstance().hasPendingInvite(chr)) {
                return false;
            }
            if (bot instanceof OPQBot opq) {
                return point.equals(PqRecruitPoints.ORBIS)
                        && opq.getOPQBotState() == OPQBot.OPQBotState.RECRUITMENT;
            }
            return bot instanceof PartyQuestBot pq && pq.lobbyMapId() == point.recruitMap()
                    && !pq.inRunGroup(chr);
        }
    }
}
