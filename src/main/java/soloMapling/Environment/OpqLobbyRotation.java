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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.DebugUtilities.fmt;

/**
 * Keeps the OPQ recruitment lobby (200080101) from reading as a stage set of the same
 * characters on every visit: every {@link #ROTATE_INTERVAL_MS} (about two and a half
 * minutes, +/- jitter) it adds one fresh recruit bot, and after the next interval it
 * retires one member of the OLD population - add and remove on alternating ticks, so the
 * headcount only ever changes by one and each change reads as one player entering or
 * leaving.
 *
 * <p>Add happens FIRST (an extra body for one interval), then remove: a failed spawn
 * retires nothing and the population is left exactly as it was. A failed removal
 * re-rolls the counter so the same tick retries removal until it succeeds - the one-bot
 * shift cannot wedge. Only bots in {@link OPQBot.OPQBotState#RECRUITMENT} with no party
 * and no pending invite are retirement candidates - the check is repeated inside the
 * teardown call itself, so a player inviting that exact bot in the milliseconds between
 * selection and removal loses nothing more than an invite that expired.</p>
 *
 * <p>No observation gating: one character appearing or vanishing between visits is
 * ordinary MapleStory traffic, and the full cast is unrecognizable within one full
 * swap (add tick + remove tick) per bot, i.e. after roughly headcount x 2 intervals.</p>
 */
public final class OpqLobbyRotation {

    /** One swap (add tick, then remove tick) every two and a half minutes. */
    private static final long ROTATE_INTERVAL_MS = 150_000L;
    /** Jitter on the FIRST tick only; the steady cadence itself stays fixed. */
    private static final long JITTER_MS = 60_000L;

    private OpqLobbyRotation() {
    }

    // Idempotent (compareAndSet), same discipline as BotMapEntryResponder.register.
    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        long firstDelay = ROTATE_INTERVAL_MS / 2 + ThreadLocalRandom.current().nextLong(JITTER_MS);
        ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(
                OpqLobbyRotation::safeTick, firstDelay, ROTATE_INTERVAL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            debugprint(fmt("OpqLobbyRotation tick failed: {}", t.toString()));
        }
    }

    private static void tick() {
        if (swapCount() % 2 == 0) {
            addOne();
        } else {
            removeOne();
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger swaps =
            new java.util.concurrent.atomic.AtomicInteger();

    private static int swapCount() {
        return swaps.get();
    }

    // ── Add ─────────────────────────────────────────────────────────────────

    private static void addOne() {
        int created = EnvironmentManager.spawnOPQBatch(1).size();
        if (created > 0) {
            swaps.incrementAndGet();
            debugprint("OpqLobbyRotation: added 1 recruit bot");
        } else {
            // Observable failure path: map missing / graph not baked / channel full.
            debugprint("OpqLobbyRotation: spawn failed, this interval added nothing");
        }
        // A failed spawn retires nothing (removeOne only runs after a successful add),
        // so the lobby population never shrinks because of a transient failure.
    }

    // ── Remove ──────────────────────────────────────────────────────────────

    private static void removeOne() {
        Character candidate = pickRetireCandidate();
        if (candidate == null) {
            // Nothing recruitable standing there (all partied up / mid-teardown window).
            // Keep the counter on the remove phase so this tick retries removal.
            debugprint("OpqLobbyRotation: no idle recruit bot to retire, retrying next interval");
            return;
        }
        if (retire(candidate)) {
            swaps.incrementAndGet();
            debugprint(fmt("OpqLobbyRotation: retired recruit bot {}", candidate.getName()));
        }
    }

    /**
     * A recruit bot that is genuinely idle: still advertising, no party, nothing pending.
     * Checked again inside the teardown, so the window between this pick and the removal
     * is only the teardown call itself.
     */
    private static Character pickRetireCandidate() {
        List<Character> candidates = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            if (!(bot instanceof OPQBot opq)
                    || opq.getOPQBotState() != OPQBot.OPQBotState.RECRUITMENT) {
                continue;
            }
            Character chr = opq.getChr();
            if (chr == null || chr.getMap() == null
                    || chr.getMapId() != OPQConstants.OPQ_LOBBY
                    || chr.getParty() != null
                    || BotPartyQueue.getInstance().hasPendingInvite(chr)) {
                continue;
            }
            candidates.add(chr);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
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
