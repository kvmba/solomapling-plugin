package soloMapling.ArtificialPlayer.GCMoveSystem;

/**
 * The wall clock every GCMoveSystem time gate reads (down-jump cadence, no-progress stall, portal /
 * observer / reaction timers). Production is plain {@link System#currentTimeMillis()}.
 *
 * <p>Execution-sim tests drive the production tick directly with no scheduler; without a seam the
 * only way to let those wall-clock gates elapse is to {@code Thread.sleep(TICK_MS)} per tick, which
 * made {@code TowerMazeExecutionSimTest} alone cost ~4.5 minutes of pure waiting. Such a test swaps
 * in a virtual clock and advances it a tick at a time, so the gates elapse identically with zero
 * real wait. Same-package, single-writer; not for production toggling.
 */
final class MovementClock {
    private MovementClock() {
    }

    private static volatile boolean virtual = false;
    private static volatile long virtualNowMs = 0L;

    static long nowMs() {
        return virtual ? virtualNowMs : System.currentTimeMillis();
    }

    static void useVirtual(long startMs) {
        virtualNowMs = startMs;
        virtual = true;
    }

    static void advance(long ms) {
        if (virtual) {
            virtualNowMs += ms;
        }
    }

    static void reset() {
        virtual = false;
    }
}
