package soloMapling.ArtificialPlayer;

import soloMapling.Environment.EnvironmentPopulationConfig;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limit for bot spawning ("投放"): caps how many bots may be created per
 * second, spread evenly across that second rather than released in a burst.
 *
 * <p>Why this exists: every {@code BotGeneration.createBot} does a full
 * {@code Character.loadCharFromDB} (~10+ SQL queries) and then announces itself
 * to the whole map - a spawn broadcast that is O(people on map) per bot. Firing
 * ~1000 of those in a few seconds pins every core, starves the DB pool, and
 * makes the server unresponsive to real players during startup. Pacing the
 * arrivals keeps the world interactive; the population still reaches its full
 * configured size, just over a longer, calmer ramp.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>No sleeps inside the window.</b> Permits are granted back-to-back and
 *       the next window opens on schedule, so N bots/second means N permits
 *       released as fast as the machine allows, then a pause until the next
 *       second. This keeps spawn latency predictable and avoids holding a
 *       virtual thread in a sleep for every bot.</li>
 *   <li><b>Window is wall-clock aligned</b> (not "1s since last refill"), so the
 *       long-run rate is exactly the configured one even when a wave takes
 *       longer than a second to drain.</li>
 *   <li><b>{@code 0} or negative means unlimited</b> - the escape hatch for
 *       developers who want the old burst behaviour back.</li>
 * </ul>
 *
 * <p>Configured by {@code spawn_rate_per_second} in
 * {@code EnvironmentPopulation.yaml} (top level, next to {@code scale}).
 */
public final class BotSpawnThrottle {

    private BotSpawnThrottle() {
    }

    /** Sentinel for "no limit" - every acquire returns immediately. */
    public static final int UNLIMITED = 0;

    private static final long WINDOW_MS = 1000L;

    private static final Object LOCK = new Object();

    // Start of the current window (epoch ms).
    private static long windowStartMs = 0L;
    // Permits already handed out in the current window.
    private static final AtomicInteger usedInWindow = new AtomicInteger(0);

    // Test seam: when non-null, overrides the YAML-derived limit. Production
    // code never sets it, so the configured value always wins at runtime.
    private static volatile Integer overrideLimit;

    /** How many bots may be created per second. {@code <= 0} means unlimited. */
    public static int limitPerSecond() {
        Integer override = overrideLimit;
        return override != null ? override : EnvironmentPopulationConfig.plan().spawnRatePerSecond();
    }

    /**
     * Force the limit, bypassing the YAML. Pass {@code null} to restore the
     * configured value. Intended for tests and for the live-tuning command.
     */
    public static void setLimitForTest(Integer limit) {
        overrideLimit = limit;
        reset();
    }

    /** True when pacing is active (limit configured above zero). */
    public static boolean enabled() {
        return limitPerSecond() > 0;
    }

    /**
     * Blocks until this caller is allowed to create one bot. Returns immediately
     * when the limit is unlimited. Interruptions are propagated as an unchecked
     * failure so a shutting-down server doesn't keep spawning.
     */
    public static void acquire() {
        int limit = limitPerSecond();
        if (limit <= 0) {
            return; // unlimited - keep the historical burst behaviour
        }

        while (true) {
            long deadline;
            synchronized (LOCK) {
                rollWindowIfDue();

                // The ONLY place a permit is granted, and it is always guarded by
                // this check. The post-sleep path below falls back into this same
                // block, so a herd of threads waking on one deadline can never all
                // be granted - each either takes a permit that is actually free or
                // computes the next deadline and sleeps again.
                if (usedInWindow.get() < limit) {
                    int used = usedInWindow.incrementAndGet();
                    java.util.function.IntConsumer probe = overflowProbe;
                    if (probe != null && used > limit) {
                        probe.accept(used);
                    }
                    return;
                }
                deadline = windowStartMs + WINDOW_MS;
            }

            // Sleep OUTSIDE the lock: a waiting bot costs no CPU, and other threads
            // can still observe the true window state instead of queueing on us.
            sleepUntil(deadline);
            // Loop: re-enter the lock, roll the window if it has come due, and
            // re-check the quota. Waking is a hint, not a permit.
        }
    }

    /**
     * Opens the next window when the current one has expired.
     * Caller must hold {@link #LOCK}.
     *
     * <p>Windows ALWAYS advance by exactly {@code WINDOW_MS} - never resynced to
     * "now". That is what makes the rate bound hold: a window's end is a fixed
     * point in time, so the permits granted in any 1s interval are bounded even
     * when a stall lets several windows elapse at once.
     *
     * <p>Resyncing to now (the obvious-looking optimisation) silently breaks that
     * bound: threads still sleeping on an older deadline wake late, find a fresh
     * window already open, and are granted on top of the permits that window
     * already handed out - measured 12 permits in one second against a limit of 8.
     * Idle periods are not worth that: after a long stall the first few windows
     * are simply spent catching up, which is the correct (conservative) behaviour
     * for something whose whole job is to protect the server from a flood.
     */
    private static void rollWindowIfDue() {
        long now = System.currentTimeMillis();
        if (windowStartMs == 0L) {
            windowStartMs = now;
            usedInWindow.set(0);
            return;
        }
        if (now - windowStartMs < WINDOW_MS) {
            return; // current window still live
        }
        windowStartMs += WINDOW_MS;
        usedInWindow.set(0);
    }

    /**
     * Blocks until this caller is allowed to create {@code count} bots. Used by
     * batch spawn sites so a cohort of 25 doesn't blow the whole window and then
     * leave the next caller waiting a full second.
     */
    public static void acquire(int count) {
        if (count <= 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            acquire();
        }
    }

    /** Reset the window. Only for tests / config reload; safe to call at any time. */
    public static void reset() {
        synchronized (LOCK) {
            windowStartMs = 0L;
            usedInWindow.set(0);
        }
    }

    /** Permits handed out in the current window (diagnostics / tests). */
    public static int usedInWindow() {
        synchronized (LOCK) {
            return usedInWindow.get();
        }
    }

    // Test-only hook: fired with the offending count if a grant ever pushes the
    // window past the limit. Lets tests assert the rate invariant from inside the
    // throttle, instead of trying to infer it from wall-clock timestamps taken
    // after each grant (which are skewed by scheduling and by the two clocks
    // involved - currentTimeMillis inside, nanoTime in the test).
    private static volatile java.util.function.IntConsumer overflowProbe;

    /** Install a probe called with the permit count on any over-limit grant. Tests only. */
    static void setOverflowProbe(java.util.function.IntConsumer probe) {
        overflowProbe = probe;
    }

    /**
     * Permits still available in the current window (for diagnostics).
     *
     * <p>Read-only: it must not roll the window, or a caller polling this to decide
     * whether to spawn would open windows early and inflate the real rate. So it
     * mirrors {@link #rollWindowIfDue}'s expiry test without mutating anything.
     */
    public static int remaining() {
        int limit = limitPerSecond();
        if (limit <= 0) {
            return Integer.MAX_VALUE;
        }
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            boolean expired = windowStartMs == 0L
                    || (now - windowStartMs >= WINDOW_MS);
            if (expired) {
                return limit; // the next acquire() will open a fresh window
            }
            return Math.max(0, limit - usedInWindow.get());
        }
    }

    /**
     * Estimated seconds until {@code count} more bots may be created, at the
     * configured rate. Zero when unlimited or already available.
     */
    public static double estimatedWaitSeconds(int count) {
        int limit = limitPerSecond();
        if (limit <= 0 || count <= 0) {
            return 0.0;
        }
        return Math.max(0.0, (count - remaining()) / (double) limit);
    }

    private static void sleepUntil(long deadlineMs) {
        long remainingMs = deadlineMs - System.currentTimeMillis();
        while (remainingMs > 0) {
            try {
                Thread.sleep(remainingMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("bot spawn throttle interrupted", e);
            }
            remainingMs = deadlineMs - System.currentTimeMillis();
        }
    }
}
