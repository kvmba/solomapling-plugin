package soloMapling.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Fable Phase 2: the central macro-brain tick wheel. Replaces the per-bot
// Executors.newScheduledThreadPool(1) in BotSM (one dedicated OS thread per bot)
// with one shared driver task that dispatches due ticks onto virtual threads.
//
// Semantics preserved from the old per-bot scheduleWithFixedDelay:
//   - never two concurrent ticks for one bot (per-entry 'ticking' CAS guard; the
//     completion's volatile writes also give tick N happens-before tick N+1)
//   - the steady-state period is measured from tick COMPLETION, so a slow tick
//     can never pile up behind itself
//   - reschedule/nudge move only this bot's next due time - no future objects
//     are cancelled or recreated
// Virtual-thread dispatch means a tick that still calls Thread.sleep (several bot
// types do) parks its own virtual thread only - it can't stall other bots.
public final class BotTickService {

    private BotTickService() {
    }

    // Driver pass cadence. Each pass is one O(entries) scan of cheap volatile reads;
    // 100ms keeps nudge latency well under the 150-700ms map-entry stagger.
    private static final long DRIVER_PERIOD_MS = 100;

    private static final class Entry {
        final Runnable tick;
        volatile long periodMs;
        volatile long nextDueMs;
        // A fire-at time requested while a tick was in flight (nudge/reschedule);
        // consumed by that tick's completion so wakeups are never lost. 0 = none.
        volatile long pendingDueMs;
        // High-priority foreground bots (FollowerBot) opt out of governor stretching -
        // their cadence IS the feature; degrading it breaks the bot, not just its polish.
        volatile boolean noThrottle;
        final AtomicBoolean ticking = new AtomicBoolean(false);

        Entry(Runnable tick, long periodMs, long nextDueMs) {
            this.tick = tick;
            this.periodMs = periodMs;
            this.nextDueMs = nextDueMs;
        }
    }

    private static final Map<Integer, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicBoolean DRIVER_STARTED = new AtomicBoolean(false);
    /** The wheel's scheduled driver, so {@link #shutdown()} can cancel it. */
    private static volatile ScheduledFuture<?> driverTask;

    // ── Governor v1 (Fable Phase 5): self-regulating degrade ────────────────
    // If the wheel falls behind (sustained avg dispatch lag over a 5s window above
    // LAG_HI), stretch every steady-state period by an escalating factor so the load
    // sheds itself; relax symmetrically once lag recovers below LAG_LO. Nudges and
    // explicit reschedules are never scaled - promotion responsiveness is sacred,
    // only the background cadence degrades. All governor fields are touched solely
    // on the single driver thread.
    private static final long GOVERNOR_WINDOW_MS = 5000;
    private static final long LAG_HI_MS = 1000;
    private static final long LAG_LO_MS = 300;
    private static final double FACTOR_MAX = 4.0;
    private static final double FACTOR_STEP = 1.5;

    private static volatile double throttleFactor = 1.0;
    private static long governorWindowStartMs = 0;
    private static long windowLagSumMs = 0;
    private static long windowDispatches = 0;

    public static double throttleFactor() {
        return throttleFactor;
    }

    // Register a bot's tick on the wheel.
    //
    // "Keep-if-present" is per FSM INSTANCE, not per character id: re-arming the same object must
    // leave a live schedule alone (the old startScheduledTask did), but a fresh BotSM wrapping the
    // same Character has to replace the previous entry. A plain putIfAbsent kept the OLD runnable,
    // so after a re-type the replacement FSM never got a wheel slot while the retired one kept
    // driving the character.
    public static void register(int botId, Runnable tick, long initialDelayMs, long periodMs) {
        ensureDriver();
        long due = System.currentTimeMillis() + initialDelayMs;
        ENTRIES.compute(botId, (id, existing) -> {
            if (existing != null && existing.tick == tick) {
                return existing; // same FSM re-arming: keep its live cadence
            }
            return new Entry(tick, periodMs, due);
        });
    }

    public static void unregister(int botId) {
        ENTRIES.remove(botId);
    }

    /**
     * Remove this FSM's entry only.
     *
     * <p>Every stopper must use this form. A plain {@link #unregister(int)} by id can delete an entry
     * that a restarted FSM just installed: the stop and the restart are issued from different threads
     * (a GM command vs the chat dispatcher), and the losing interleaving leaves the bot running with
     * no wheel entry - it never ticks again and no path re-registers it.
     */
    public static void unregister(int botId, Runnable tick) {
        ENTRIES.computeIfPresent(botId, (id, existing) -> existing.tick == tick ? null : existing);
    }

    public static boolean isRegistered(int botId) {
        return ENTRIES.containsKey(botId);
    }

    // Exempt a bot's steady cadence from governor stretching. Call after register;
    // a conversion re-registers a fresh entry, so the new type must re-assert it.
    public static void setNoThrottle(int botId, boolean on) {
        Entry e = ENTRIES.get(botId);
        if (e != null) {
            e.noThrottle = on;
        }
    }

    public static int size() {
        return ENTRIES.size();
    }

    // Change a bot's cadence: next fire at now + periodMs, then periodMs after each
    // completion (matches the old cancel + re-scheduleWithFixedDelay semantics).
    public static void reschedule(int botId, long periodMs) {
        Entry e = ENTRIES.get(botId);
        if (e == null) {
            return;
        }
        e.periodMs = periodMs;
        requestFireAt(e, System.currentTimeMillis() + periodMs);
    }

    // Pull the next fire forward to now + initialDelayMs and set the steady period.
    public static void nudge(int botId, long initialDelayMs, long periodMs) {
        Entry e = ENTRIES.get(botId);
        if (e == null) {
            return;
        }
        e.periodMs = periodMs;
        requestFireAt(e, System.currentTimeMillis() + initialDelayMs);
    }

    // Ask for a fire at 'at'. pendingDueMs is set first so an in-flight tick's
    // completion always sees the request - a wakeup can never be lost to the race
    // between this write and the driver parking nextDueMs for a dispatch.
    private static void requestFireAt(Entry e, long at) {
        e.pendingDueMs = at;
        if (e.nextDueMs != Long.MAX_VALUE) { // not mid-tick: move the wheel entry directly
            e.nextDueMs = at;
        }
    }

    private static void ensureDriver() {
        if (!DRIVER_STARTED.compareAndSet(false, true)) {
            return;
        }
        driverTask = ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(
                BotTickService::safeDrive, DRIVER_PERIOD_MS, DRIVER_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop the wheel and forget every registered tick, so a later registration starts a fresh
     * driver instead of silently joining a dead one.
     *
     * <p>Every bot FSM arms its tick through this wheel. Leaving the driver running past an
     * unload keeps those FSMs ticking against characters the plugin already tore down; leaving
     * {@code DRIVER_STARTED} set after cancelling it would leave the survivors with no driver at
     * all.
     */
    public static void shutdown() {
        ScheduledFuture<?> task = driverTask;
        driverTask = null;
        if (task != null) {
            task.cancel(false);
        }
        ENTRIES.clear();
        DRIVER_STARTED.set(false);
    }

    private static void safeDrive() {
        try {
            drive();
        } catch (Throwable t) {
            // the driver must never die; skip the pass and catch up on the next one
        }
    }

    private static void drive() {
        long now = System.currentTimeMillis();
        long lagSum = 0;
        int dispatched = 0;
        for (Entry e : ENTRIES.values()) {
            if (now < e.nextDueMs) {
                continue;
            }
            if (!e.ticking.compareAndSet(false, true)) {
                continue; // still running; its completion computes the next due time
            }
            long lag = now - e.nextDueMs;
            e.nextDueMs = Long.MAX_VALUE; // parked while ticking
            if (e.pendingDueMs > 0 && now >= e.pendingDueMs) {
                e.pendingDueMs = 0; // this dispatch satisfies the pending request
            }
            BotPerfStats.recordWheelDispatch(lag);
            lagSum += Math.max(0, lag);
            dispatched++;
            ExecutorServiceManager.runAsync(() -> runTick(e));
        }
        governorTick(now, lagSum, dispatched);
    }

    private static void governorTick(long now, long lagSumMs, int dispatches) {
        windowLagSumMs += lagSumMs;
        windowDispatches += dispatches;
        if (governorWindowStartMs == 0) {
            governorWindowStartMs = now;
            return;
        }
        if (now - governorWindowStartMs < GOVERNOR_WINDOW_MS) {
            return;
        }
        long avg = windowDispatches > 0 ? windowLagSumMs / windowDispatches : 0;
        windowLagSumMs = 0;
        windowDispatches = 0;
        governorWindowStartMs = now;
        if (avg > LAG_HI_MS && throttleFactor < FACTOR_MAX) {
            throttleFactor = Math.min(FACTOR_MAX, throttleFactor * FACTOR_STEP);
            System.out.println(String.format(
                    "[BotTickService] wheel lag avg %dms - stretching cadences x%.2f", avg, throttleFactor));
        } else if (avg < LAG_LO_MS && throttleFactor > 1.0) {
            throttleFactor = Math.max(1.0, throttleFactor / FACTOR_STEP);
            System.out.println(String.format(
                    "[BotTickService] wheel recovered (avg %dms) - cadence factor x%.2f", avg, throttleFactor));
        }
    }

    private static void runTick(Entry e) {
        try {
            e.tick.run();
        } catch (Throwable t) {
            // one bot's tick error must never kill its schedule (tickRunnable also guards)
        } finally {
            long now = System.currentTimeMillis();
            long due = now + (e.noThrottle ? e.periodMs
                    : (long) (e.periodMs * throttleFactor)); // governor stretches steady cadence only
            long pending = e.pendingDueMs;
            if (pending > 0) {
                e.pendingDueMs = 0;
                due = Math.min(due, Math.max(pending, now));
            }
            e.nextDueMs = due;       // write the due time BEFORE releasing the guard
            e.ticking.set(false);
        }
    }
}
