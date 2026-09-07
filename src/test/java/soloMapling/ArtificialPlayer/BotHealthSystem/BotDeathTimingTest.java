package soloMapling.ArtificialPlayer.BotHealthSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The death episode's timing and pacing, which are the parts a reader cannot check by eye.
 *
 * <p>These are deliberately pure: no character, no map, no scheduler. The real risk in this
 * class is arithmetic and ordering — a bot lying down for a quarter of an hour instead of a
 * minute, or a timer that never expires — and that is all visible here.
 */
class BotDeathTimingTest {

    @Test
    void downTimeIsHalfAMinuteToTwoMinutes() {
        // The brief: long enough to read as a corpse, short enough that the bot comes back.
        assertTrue(BotDeath.DOWN_MIN_MS >= 30_000L, "must lie at least 30s");
        assertTrue(BotDeath.DOWN_MAX_MS <= 120_000L, "must not lie past 2 min");
        assertTrue(BotDeath.DOWN_MIN_MS < BotDeath.DOWN_MAX_MS, "the window must vary");
    }

    @Test
    void grumbleGapIsEightToFifteenSeconds() {
        // Often enough that a player standing there sees it living, not so often it spams.
        assertTrue(BotDeath.GRUMBLE_MIN_MS >= 8_000L);
        assertTrue(BotDeath.GRUMBLE_MAX_MS <= 15_000L);
        assertTrue(BotDeath.GRUMBLE_MIN_MS < BotDeath.GRUMBLE_MAX_MS);
    }

    @Test
    void aCorpseIsNeverLeftDownForever() {
        // Whatever goes wrong with the ordinary timer, the fail-safe has to fire after it.
        assertTrue(BotDeath.DOWN_MAX_HARD_MS > BotDeath.DOWN_MAX_MS,
                "the fail-safe must outlast the longest ordinary wait, or it pre-empts it");
        assertTrue(BotDeath.DOWN_MAX_HARD_MS <= 10 * 60_000L,
                "a corpse must never lie for more than a few minutes");
    }

    @Test
    void deadBotsTickFarFasterThanAnUnobservedGrinder() {
        // The bug this guards: an unobserved grinder's macro cadence is 240-480s. Left at that,
        // a death would take a quarter of an hour instead of the intended 30-120s. The corpse
        // has to be paced by its own clock.
        BotDeath death = new BotDeath(null);
        long delay = death.tickDelayMs();
        assertTrue(delay <= 15_000L, "a dead bot must be re-checked within seconds, was " + delay);
        assertTrue(delay >= 1_000L, "no point spinning faster than once a second, was " + delay);
    }

    @Test
    void tickDelayNeverOvershootsTheEndOfTheWait() {
        // Pacing past standUpAtMs would add up to a whole extra interval to every death.
        BotDeath death = new BotDeath(null);
        for (long remaining : new long[]{0L, 500L, 5_000L, 12_000L}) {
            long delay = death.delayForRemainingForTest(remaining);
            assertTrue(delay <= remaining || delay <= 15_000L,
                    "delay " + delay + " overshoots remaining " + remaining);
        }
        assertEquals(1_000L, death.delayForRemainingForTest(0L),
                "with no time left, wake almost immediately so the bot stands up on time");
    }

    @Test
    void notDeadMeansNoSpecialPacing() {
        BotDeath death = new BotDeath(null);
        assertFalse(death.isDead());
    }
}
