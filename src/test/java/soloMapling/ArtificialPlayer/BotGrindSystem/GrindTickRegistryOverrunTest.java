package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The combat sweep runs on a fixed-rate scheduler, so a sweep that overruns its
 * period is simply run back-to-back: combat silently degrades from 4Hz to whatever
 * the machine can sustain, with no record that it happened. These tests pin the
 * rate-limit gate behind the warning - it must fire on the first overrun and then
 * go quiet for the rest of the window, rather than logging on every 250ms sweep.
 */
class GrindTickRegistryOverrunTest {

    private static GrindTickRegistry registry() {
        // The scheduler is never invoked; only sweep-time behaviour is under test.
        return new GrindTickRegistry(scheduler -> { });
    }

    @Test
    void firstOverrunWarns() {
        GrindTickRegistry registry = registry();
        assertTrue(registry.shouldWarnOverrun(1_000L), "the first overrun must be reported");
    }

    @Test
    void sustainedOverrunIsRateLimitedToOneWarningPerWindow() {
        GrindTickRegistry registry = registry();
        AtomicLong now = new AtomicLong(1_000L);

        int warnings = 0;
        // 40s of back-to-back 250ms sweeps, all over budget.
        for (long t = 0; t < 40_000L; t += GrindTickRegistry.TICK_MS) {
            if (registry.shouldWarnOverrun(now.addAndGet(GrindTickRegistry.TICK_MS))) {
                warnings++;
            }
        }
        assertEquals(2, warnings,
                "a continuous 40s overrun should warn about once per 30s window, not 160 times");
    }

    @Test
    void warningResumesAfterTheWindowElapses() {
        GrindTickRegistry registry = registry();
        assertTrue(registry.shouldWarnOverrun(1_000L));
        assertFalse(registry.shouldWarnOverrun(1_000L + 1_000L), "still inside the window");
        assertFalse(registry.shouldWarnOverrun(1_000L + 29_999L), "still inside the window");
        assertTrue(registry.shouldWarnOverrun(1_000L + 30_000L), "window elapsed - warn again");
    }
}
