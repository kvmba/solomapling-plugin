package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.gms.client.BotTier;
import org.junit.jupiter.api.Test;

import java.util.IntSummaryStatistics;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies bot fame generation without starting the game server.
 *
 * <p>Bots used to inherit their internal character id as fame (a debug leftover
 * in {@code BotGeneration}), so every artificial player showed a five-digit
 * reputation. These tests pin the replacement distribution to the intended
 * shape: low-level bots stay near zero and can be negative, the population caps
 * at {@link BotFame#MAX_FAME}, and negatives actually occur.
 */
class BotFameTest {

    private static final int SAMPLES = 20000;

    /** Well above any bot id (bots start at 20000) - the old debug value. */
    @Test
    void lowLevelBotsStayNearZero() {
        Random rng = new Random(1);
        IntSummaryStatistics stats = sample(5, BotTier.C, rng);

        assertTrue(stats.getMin() >= -10, "level 5 min fame was " + stats.getMin());
        assertTrue(stats.getMax() <= 30, "level 5 max fame was " + stats.getMax());
        assertTrue(stats.getAverage() < 10, "level 5 average fame was " + stats.getAverage());
    }

    @Test
    void beginnerBandMatchesRequestedRange() {
        Random rng = new Random(2);
        // The user-facing requirement: low-level bots land in roughly -10..30.
        IntSummaryStatistics stats = sample(10, BotTier.B, rng);
        assertTrue(stats.getMin() >= -12, "level 10 min fame was " + stats.getMin());
        assertTrue(stats.getMax() <= 30, "level 10 max fame was " + stats.getMax());
    }

    @Test
    void neverExceedsHardCeilingAtAnyLevelOrTier() {
        Random rng = new Random(3);
        for (BotTier tier : BotTier.values()) {
            for (int level : new int[]{1, 50, 100, 150, 200}) {
                for (int i = 0; i < 2000; i++) {
                    int fame = BotFame.generate(level, tier, rng);
                    assertTrue(fame <= BotFame.MAX_FAME,
                            "fame " + fame + " exceeded cap at lv" + level + " " + tier);
                    assertTrue(fame >= BotFame.MIN_FAME,
                            "fame " + fame + " below floor at lv" + level + " " + tier);
                }
            }
        }
    }

    @Test
    void negativeFameOccurs() {
        Random rng = new Random(4);
        IntSummaryStatistics stats = sample(80, BotTier.B, rng);
        assertTrue(stats.getMin() < 0, "no negative fame in " + SAMPLES + " rolls");

        long negatives = countNegatives(80, BotTier.B, rng);
        // Configured at 15%; allow generous sampling slack.
        assertTrue(negatives > SAMPLES * 0.10 && negatives < SAMPLES * 0.20,
                "negative share was " + (negatives / (double) SAMPLES));
    }

    @Test
    void fameGrowsWithLevel() {
        Random rng = new Random(5);
        double low = sample(20, BotTier.B, rng).getAverage();
        double mid = sample(100, BotTier.B, rng).getAverage();
        double high = sample(200, BotTier.B, rng).getAverage();

        assertTrue(low < mid, "lv20 avg " + low + " should be below lv100 avg " + mid);
        assertTrue(mid < high, "lv100 avg " + mid + " should be below lv200 avg " + high);
    }

    @Test
    void highLevelBotsAreMostlyModest() {
        Random rng = new Random(6);
        IntSummaryStatistics stats = sample(200, BotTier.B, rng);
        // The distribution is skewed toward zero, so a maxed-out bot should be
        // nowhere near the ceiling on average and only a few approach it.
        assertTrue(stats.getAverage() < 90, "lv200 B-tier average fame was " + stats.getAverage());
        assertTrue(stats.getMax() > 100, "lv200 B-tier should reach triple digits, max was " + stats.getMax());
    }

    @Test
    void tiersOrderReputation() {
        Random rng = new Random(7);
        double s = sample(120, BotTier.S, rng).getAverage();
        double b = sample(120, BotTier.B, rng).getAverage();
        double d = sample(120, BotTier.D, rng).getAverage();

        assertTrue(s > b, "S-tier avg " + s + " should exceed B-tier avg " + b);
        assertTrue(b > d, "B-tier avg " + b + " should exceed D-tier avg " + d);
    }

    @Test
    void clampsOutOfRangeLevels() {
        Random rng = new Random(8);
        // Defensive: a bogus level (template character or a bad GM arg) must not
        // produce a wild reputation.
        for (int i = 0; i < 500; i++) {
            int fame = BotFame.generate(0, BotTier.S, rng);
            assertTrue(fame <= BotFame.MAX_FAME && fame >= BotFame.MIN_FAME, "lv0 fame " + fame);
            fame = BotFame.generate(9999, BotTier.S, rng);
            assertTrue(fame <= BotFame.MAX_FAME && fame >= BotFame.MIN_FAME, "lv9999 fame " + fame);
        }
    }

    @Test
    void nullTierFallsBackToDefault() {
        // Character.getTier() is null-safe, but a bot decorated before its tier is
        // assigned must still get a sane roll rather than an NPE.
        assertEquals(BotFame.generate(50, BotTier.C, new Random(9)),
                BotFame.generate(50, null, new Random(9)));
    }

    private static IntSummaryStatistics sample(int level, BotTier tier, Random rng) {
        IntSummaryStatistics stats = new IntSummaryStatistics();
        for (int i = 0; i < SAMPLES; i++) {
            stats.accept(BotFame.generate(level, tier, rng));
        }
        return stats;
    }

    private static long countNegatives(int level, BotTier tier, Random rng) {
        long negatives = 0;
        for (int i = 0; i < SAMPLES; i++) {
            if (BotFame.generate(level, tier, rng) < 0) {
                negatives++;
            }
        }
        return negatives;
    }
}
