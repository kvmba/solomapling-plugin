package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.BotPerfStats;
import soloMapling.server.ExecutorServiceManager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide 250ms combat sweep shared by every GrindBrain owner.
 *
 * <p>Participants own their lifecycle and gating. The registry only guarantees
 * one scheduled task, identity-based registration, and per-participant failure
 * isolation.</p>
 */
public final class GrindTickRegistry {

    private static final Logger log = LoggerFactory.getLogger(GrindTickRegistry.class);
    private static final long FAILURE_LOG_INTERVAL_MS = 10_000L;
    public static final long TICK_MS = 250L;

    // A sweep that overruns its own period makes the shared scheduler run it back-to-back:
    // combat silently degrades from 4Hz to whatever the machine can sustain, with no record
    // that it happened. Warn (rate-limited) so an over-budget sweep is diagnosable instead of
    // showing up only as "bots feel sluggish".
    private static final long OVERRUN_LOG_INTERVAL_MS = 30_000L;
    private volatile long nextOverrunLogAt = 0L;

    @FunctionalInterface
    public interface Participant {
        void grindTick();
    }

    @FunctionalInterface
    interface TickerScheduler {
        /**
         * Arm the sweep. Returns the scheduled task (or null when a test scheduler runs inline),
         * so {@link #shutdown()} can actually cancel the periodic task instead of leaving it to
         * wake up every {@link #TICK_MS} forever.
         */
        java.util.concurrent.ScheduledFuture<?> schedule(Runnable ticker);
    }

    private static final GrindTickRegistry INSTANCE = new GrindTickRegistry();

    private final Set<Participant> participants = ConcurrentHashMap.newKeySet();
    private final Map<Participant, Long> nextFailureLogAt = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final TickerScheduler scheduler;

    /** The armed sweep; cancelled by {@link #shutdown()} so it cannot outlive the plugin. */
    private volatile java.util.concurrent.ScheduledFuture<?> sweepTask;

    private GrindTickRegistry() {
        this(ticker -> ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(
                ticker, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS));
    }

    GrindTickRegistry(TickerScheduler scheduler) {
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
    }

    public static GrindTickRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized void register(Participant participant) {
        if (participant == null) {
            return;
        }
        // Add first so even a scheduler that invokes the task immediately sees
        // the registering participant. A failed schedule rolls this add back.
        boolean added = participants.add(participant);
        try {
            ensureStarted();
        } catch (RuntimeException | Error failure) {
            if (added) {
                participants.remove(participant);
            }
            throw failure;
        }
    }

    public void unregister(Participant participant) {
        if (participant != null) {
            participants.remove(participant);
            nextFailureLogAt.remove(participant);
        }
    }

    public int participantCount() {
        return participants.size();
    }

    public int participantCount(Class<?> participantType) {
        if (participantType == null) {
            return 0;
        }
        int count = 0;
        for (Participant participant : participants) {
            if (participantType.isInstance(participant)) {
                count++;
            }
        }
        return count;
    }

    private void ensureStarted() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            sweepTask = scheduler.schedule(this::sweep);
        } catch (RuntimeException | Error failure) {
            started.set(false);
            throw failure;
        }
    }

    /**
     * Stop the shared sweep for good.
     *
     * <p>Every grinder registers here. A sweep that outlives the plugin keeps calling
     * {@code grindTick()} on combat brains whose characters the unload already removed, and
     * this registry never re-arms (the plugin is not loaded twice in one process), so the
     * periodic task has to be cancelled rather than just emptied.
     */
    public synchronized void shutdown() {
        java.util.concurrent.ScheduledFuture<?> task = sweepTask;
        sweepTask = null;
        if (task != null) {
            task.cancel(false);
        }
        started.set(true); // keep it latched: re-arming after an unload would leak a second sweep
        participants.clear();
        nextFailureLogAt.clear();
    }

    void sweep() {
        long sweepStart = System.currentTimeMillis();
        for (Participant participant : participants) {
            try {
                participant.grindTick();
            } catch (Throwable failure) {
                // A broken participant must never terminate the shared ticker, but
                // suppressing the failure entirely makes a live combat stall impossible
                // to diagnose.
                long now = System.currentTimeMillis();
                Long next = nextFailureLogAt.get(participant);
                if (next == null || next <= now) {
                    nextFailureLogAt.put(participant, now + FAILURE_LOG_INTERVAL_MS);
                    log.error("Grind participant tick failed type={}",
                            participant.getClass().getName(), failure);
                }
            }
        }
        long elapsedMs = System.currentTimeMillis() - sweepStart;
        BotPerfStats.recordCombatSweep(elapsedMs, participants.size());
        if (elapsedMs > TICK_MS) {
            logOverrun(elapsedMs, participants.size());
        }
    }

    private void logOverrun(long elapsedMs, int count) {
        if (!shouldWarnOverrun(System.currentTimeMillis())) {
            return;
        }
        log.warn("Grind sweep overran its {}ms period: took {}ms for {} participant(s) - "
                + "combat cadence is degraded; reduce grinder count or per-tick work",
                TICK_MS, elapsedMs, count);
    }

    /*
     * Rate-limit gate for the overrun warning: true at most once per
     * OVERRUN_LOG_INTERVAL_MS. Driven by an explicit clock so a test can advance
     * time - a sustained overrun would otherwise warn on every 250ms sweep.
     */
    boolean shouldWarnOverrun(long nowMs) {
        if (nowMs < nextOverrunLogAt) {
            return false;
        }
        nextOverrunLogAt = nowMs + OVERRUN_LOG_INTERVAL_MS;
        return true;
    }
}
