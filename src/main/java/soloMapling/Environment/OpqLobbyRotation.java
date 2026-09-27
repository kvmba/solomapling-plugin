package soloMapling.Environment;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.server.ExecutorServiceManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.DebugUtilities.fmt;

/**
 * Keeps the OPQ recruitment lobby (200080101) from reading as a stage set of the same
 * characters on every visit: every {@link #ROTATE_INTERVAL_MS} it adds one fresh recruit
 * bot, and on the next tick it retires the OLDEST member of the original population -
 * add and remove on alternating ticks, so the headcount only ever changes by one and
 * each change reads as one player entering or leaving.
 *
 * <p>Oldest-first, not random: the whole point is that a player returning much later
 * sees nobody they recognize. A random pick lets newcomers join the retirement pool, so
 * an original bot can sit un-retired for hours while freshly-rotated ones come and go;
 * FIFO guarantees every startup bot is gone after one add + remove pass over the
 * population (about {@code headcount x 2} intervals). Bots that retired before they
 * could ever be seen (all partied up on a given tick) are simply skipped - they were
 * never seen by anyone either, so their "generation" is dropped without blocking the
 * queue behind them.</p>
 *
 * <p>Add happens FIRST (an extra body for one interval), then remove: a failed spawn
 * retires nothing and the population is left exactly as it was. A failed removal keeps
 * the counter on the remove phase so the same tick retries until it succeeds - the
 * one-bot shift cannot wedge. Only bots in {@link OPQBot.OPQBotState#RECRUITMENT} with
 * no party and no pending invite are retirement candidates - the check is repeated
 * inside the teardown call itself, so a player inviting that exact bot in the
 * milliseconds between selection and removal loses nothing more than an invite that
 * expired.</p>
 *
 * <p>No observation gating: one character appearing or vanishing between visits is
 * ordinary MapleStory traffic. Honors the operator's {@code opq_lobby} wave switch -
 * a lobby the startup was told not to populate is not repopulated on the sly either.</p>
 */
public final class OpqLobbyRotation {

    /** One swap (add tick, then remove tick) every two and a half minutes. */
    private static final long ROTATE_INTERVAL_MS = 150_000L;
    /** Jitter on the FIRST tick only; the steady cadence itself stays fixed. */
    private static final long JITTER_MS = 60_000L;

    private OpqLobbyRotation() {
    }

    // Idempotent (compareAndSet), same discipline as BotMapEntryResponder.register.
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    // Even = add one; odd = retire one. A failed removal leaves it odd, so the next
    // tick retries the removal instead of stacking another body.
    private static final AtomicInteger swaps = new AtomicInteger();

    /**
     * botId -> epoch-ms the bot joined the population, oldest-retired-first order.
     * Written on the rotation thread only; a ConcurrentHashMap so the startup-spawned
     * cohort registered before this class ever started coexists with rotated-in bots.
     */
    private static final Map<Integer, Long> joinedAtMs = new ConcurrentHashMap<>();

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        // Honor the operator's wave switch: opq_lobby: false means "leave the Orbis PQ
        // lobby empty", so the rotator must not fill it either.
        if (!EnvironmentPopulationConfig.plan().lateArrivals().opqLobby()) {
            debugprint("OpqLobbyRotation: opq_lobby disabled in EnvironmentPopulation.yaml, not started");
            return;
        }
        long firstDelay = ROTATE_INTERVAL_MS / 2 + ThreadLocalRandom.current().nextLong(JITTER_MS);
        ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(
                OpqLobbyRotation::safeTick, firstDelay, ROTATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            debugprint(fmt("OpqLobbyRotation tick failed: {}", t.toString()));
        }
    }

    private static void tick() {
        if (swaps.get() % 2 == 0) {
            addOne();
        } else {
            removeOne();
        }
    }

    // ── Add ─────────────────────────────────────────────────────────────────

    private static void addOne() {
        var created = EnvironmentManager.spawnOPQBatch(1);
        if (created.isEmpty()) {
            // Observable failure path: map missing / graph not baked / channel full.
            // Nothing was added, so retire nothing - the population never shrinks on a
            // transient failure, and the next tick retries the add.
            debugprint("OpqLobbyRotation: spawn failed, this interval added nothing");
            return;
        }
        joinedAtMs.put(created.get(0), System.currentTimeMillis());
        swaps.incrementAndGet();
        debugprint("OpqLobbyRotation: added 1 recruit bot");
    }

    // ── Remove ──────────────────────────────────────────────────────────────

    private static void removeOne() {
        Character candidate = pickRetireCandidate();
        if (candidate == null) {
            // No tracked bot is recruitable right now (mid-teardown window, all partied
            // up, or the startup cohort predates this class). Keep the counter on the
            // remove phase so this tick retries removal.
            debugprint("OpqLobbyRotation: no tracked recruit bot to retire, retrying next interval");
            return;
        }
        if (retire(candidate)) {
            joinedAtMs.remove(candidate.getId());
            swaps.incrementAndGet();
            debugprint(fmt("OpqLobbyRotation: retired recruit bot {}", candidate.getName()));
        }
    }

    /**
     * The oldest tracked bot that is genuinely idle: still advertising, no party,
     * nothing pending. Only bots this class has seen spawn are tracked - the startup
     * cohort registered before start() ran, so its members enroll as they are picked:
     * the first removeOne() pass finds none tracked, enrolls the whole live cohort at
     * one timestamp, and retires from there in spawn order. Bots that retired before
     * their turn (partied up on their tick) drop out of the map without blocking the
     * queue - they left the population, which is all FIFO exists to guarantee.
     */
    private static Character pickRetireCandidate() {
        if (joinedAtMs.isEmpty()) {
            enrollCurrentCohort();
            return null; // the fresh cohort becomes candidates from the next tick
        }
        Map.Entry<Integer, Long> oldest = null;
        for (Map.Entry<Integer, Long> entry : joinedAtMs.entrySet()) {
            if (oldest == null || entry.getValue() < oldest.getValue()) {
                oldest = entry;
            }
        }
        BotSM bot = CharacterStorage.getBotById(oldest.getKey());
        if (!(bot instanceof OPQBot opq)) {
            joinedAtMs.remove(oldest.getKey()); // stopped/converted elsewhere - not ours anymore
            return null; // retry on the next tick; the map self-cleans as entries die
        }
        Character chr = opq.getChr();
        if (chr == null || chr.getMap() == null
                || chr.getMapId() != OPQConstants.OPQ_LOBBY
                || opq.getOPQBotState() != OPQBot.OPQBotState.RECRUITMENT
                || chr.getParty() != null
                || BotPartyQueue.getInstance().hasPendingInvite(chr)) {
            return null; // still tracked, just not recruitable this tick - retry later
        }
        return chr;
    }

    /**
     * Enroll the bots already standing in the lobby (the startup cohort, or anything a
     * GM spawned by hand) as the founding generation, all stamped "now" so they retire
     * before anything rotated in afterwards.
     */
    private static void enrollCurrentCohort() {
        long now = System.currentTimeMillis();
        int enrolled = 0;
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            if (!(bot instanceof OPQBot opq)
                    || opq.getOPQBotState() != OPQBot.OPQBotState.RECRUITMENT) {
                continue;
            }
            Character chr = opq.getChr();
            if (chr == null || chr.getMap() == null
                    || chr.getMapId() != OPQConstants.OPQ_LOBBY) {
                continue;
            }
            joinedAtMs.putIfAbsent(chr.getId(), now);
            enrolled++;
        }
        debugprint(fmt("OpqLobbyRotation: enrolled {} existing lobby bots as the founding cohort", enrolled));
    }

    /**
     * Stop the bot's FSM and pull it off the server - the same two calls the
     * {@code !bot remove} command uses. Returns false (and touches nothing) when the bot
     * is no longer the idle recruiter it was picked as.
     */
    private static boolean retire(Character candidate) {
        BotSM bot = CharacterStorage.getBotById(candidate.getId());
        if (!(bot instanceof OPQBot opq)
                || opq.getOPQBotState() != OPQBot.OPQBotState.RECRUITMENT
                || candidate.getParty() != null
                || BotPartyQueue.getInstance().hasPendingInvite(candidate)) {
            return false;
        }
        BotTypeManager.manuallyStopBot(candidate);
        BotGeneration.removeBotFromServer(candidate);
        return true;
    }
}
