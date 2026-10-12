package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GCMovementDriverTest {
    @Test
    void compensatesForNormalTickExecutionTime() {
        assertEquals(1_050L, GCMovementDriver.nextDeadline(1_000L, 1_018L, 50L));
    }

    @Test
    void skipsMissedDeadlinesAfterAnOverrun() {
        assertEquals(1_150L, GCMovementDriver.nextDeadline(1_000L, 1_120L, 50L));
    }

    @Test
    void preservesLodCadencesAndSupportsTierChanges() {
        assertEquals(1_250L, GCMovementDriver.nextDeadline(1_000L, 1_018L, 250L));
        assertEquals(2_000L, GCMovementDriver.nextDeadline(1_000L, 1_018L, 1_000L));
        assertEquals(1_050L, GCMovementDriver.nextDeadline(1_000L, 1_018L, 50L));
    }

    @Test
    void aiTickFiresEveryOtherObservedTick() {
        BotMovementState st = new BotMovementState(null, null);
        st.lastTickSpanMs = 50L;
        assertFalse(GCMovementDriver.consumeAiTick(st), "first tick only banks half the period");
        assertTrue(GCMovementDriver.consumeAiTick(st), "second tick reaches the 100ms AI period");
        assertFalse(GCMovementDriver.consumeAiTick(st));
        assertTrue(GCMovementDriver.consumeAiTick(st));
    }

    /**
     * The regression 7ad7fd3's cadence-span change left behind: a coarse (1s/4s) wakeup banks the
     * whole span while only one 100ms decision can run per tick, so an unobserved bot accrued
     * ~0.9s of debt per second — on promotion every 50ms tick then fired the heavy AI (double rate)
     * for ~0.9x the unobserved duration, and an int overflow (after ~4 weeks unobserved) froze it
     * for weeks. The accumulator must carry at most ONE pending decision.
     */
    @Test
    void coarseCadenceFiresEveryWakeupWithoutBankingDebt() {
        BotMovementState st = new BotMovementState(null, null);
        st.lastTickSpanMs = 1_000L;
        for (int i = 0; i < 600; i++) { // ten minutes of unobserved 1s wakeups
            assertTrue(GCMovementDriver.consumeAiTick(st), "every coarse wakeup runs one AI decision");
            assertEquals(0, st.aiTickAccumulatorMs, "no debt is banked (i=" + i + ")");
        }
        // Promotion to the observed 50ms cadence: the every-other-tick rate holds immediately —
        // the pre-fix build fired every tick here until the ~540s bank drained.
        st.lastTickSpanMs = 50L;
        assertFalse(GCMovementDriver.consumeAiTick(st));
        assertTrue(GCMovementDriver.consumeAiTick(st));
    }

    @Test
    void anOverrunFiresOnceWithoutBankingTheSkippedSlots() {
        BotMovementState st = new BotMovementState(null, null);
        st.lastTickSpanMs = 250L; // an overrun spanning two skipped 50ms slots
        assertTrue(GCMovementDriver.consumeAiTick(st));
        assertEquals(0, st.aiTickAccumulatorMs);
    }
}
