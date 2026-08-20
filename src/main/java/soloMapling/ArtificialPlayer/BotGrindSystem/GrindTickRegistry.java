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

    @FunctionalInterface
    public interface Participant {
        void grindTick();
    }

    @FunctionalInterface
    interface TickerScheduler {
        void schedule(Runnable ticker);
    }

    private static final GrindTickRegistry INSTANCE = new GrindTickRegistry();

    private final Set<Participant> participants = ConcurrentHashMap.newKeySet();
    private final Map<Participant, Long> nextFailureLogAt = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final TickerScheduler scheduler;

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
            scheduler.schedule(this::sweep);
        } catch (RuntimeException | Error failure) {
            started.set(false);
            throw failure;
        }
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
        BotPerfStats.recordCombatSweep(
                System.currentTimeMillis() - sweepStart, participants.size());
    }
}
