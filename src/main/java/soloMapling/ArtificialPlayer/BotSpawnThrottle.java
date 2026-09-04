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
        long deadline;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (windowStartMs == 0L || now - windowStartMs >= WINDOW_MS) {
                // Roll to a fresh window. Align to the boundary when we merely
                // expired (so long-run rate == configured rate), or start now on
                // the very first call.
                windowStartMs = (windowStartMs == 0L || now - windowStartMs >= 2 * WINDOW_MS)
                        ? now
                        : windowStartMs + WINDOW_MS;
                usedInWindow.set(0);
            }
            if (usedInWindow.get() < limit) {
                usedInWindow.incrementAndGet();
                return;
            }
            // Window exhausted: wait for the next one, then take the first permit.
            deadline = windowStartMs + WINDOW_MS;
        }

        // Sleep OUTSIDE the lock: this bot is not consuming CPU, and other
        // threads can still observe the exhausted window instead of queueing.
        sleepUntil(deadline);

        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - windowStartMs >= WINDOW_MS) {
                windowStartMs = (now - windowStartMs >= 2 * WINDOW_MS) ? now : windowStartMs + WINDOW_MS;
                usedInWindow.set(0);
            }
            usedInWindow.incrementAndGet();
        }
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

    /** Permits still available in the current window (for diagnostics). */
    public static int remaining() {
        int limit = limitPerSecond();
        if (limit <= 0) {
            return Integer.MAX_VALUE;
        }
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (windowStartMs == 0L || now - windowStartMs >= WINDOW_MS) {
                return limit;
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
