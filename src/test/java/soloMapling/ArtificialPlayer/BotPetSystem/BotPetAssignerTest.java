package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.BotTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the pet assignment policy. No host types beyond the
 * {@link BotTier} enum, so these run anywhere.
 */
class BotPetAssignerTest {

    private static final List<Integer> POOL =
            List.of(5000000, 5000001, 5000002, 5000003, 5000004, 5000005);

    private static final double EPS = 1e-9;

    @Test
    void strengthIsMonotonicInLevelAndTier() {
        BotPetConfig c = BotPetConfig.defaults();
        double lowLevel = BotPetAssigner.strength(30, BotTier.B, c);
        double highLevel = BotPetAssigner.strength(100, BotTier.B, c);
        assertTrue(highLevel > lowLevel, "higher level => higher strength");

        double lowTier = BotPetAssigner.strength(100, BotTier.D, c);
        double highTier = BotPetAssigner.strength(100, BotTier.S, c);
        assertTrue(highTier > lowTier, "higher tier => higher strength");

        // Level cap clamps: above the cap, strength stops rising with level.
        assertEquals(BotPetAssigner.strength(c.levelCap(), BotTier.S, c),
                BotPetAssigner.strength(200, BotTier.S, c), EPS);
    }

    @Test
    void strengthStaysWithinUnitRange() {
        BotPetConfig c = BotPetConfig.defaults();
        for (int level : new int[]{1, 10, 50, 120, 200}) {
            for (BotTier tier : BotTier.values()) {
                double s = BotPetAssigner.strength(level, tier, c);
                assertTrue(s >= 0.0 && s <= 1.0, "strength in [0,1] for " + level + "/" + tier + " => " + s);
            }
        }
    }

    @Test
    void carryRateIsAboutMonotonicAndCappedAtPMax() {
        // Empirical: carry rate must be non-decreasing in level and never exceed pMax.
        BotPetConfig c = BotPetConfig.defaults();
        double prev = -1.0;
        for (int level : new int[]{10, 30, 50, 70, 90, 120}) {
            int carried = 0;
            int trials = 200_000;
            Random rng = new Random(1234 + level);
            for (int i = 0; i < trials; i++) {
                if (!BotPetAssigner.assign(level, BotTier.S, POOL, c, rng).isEmpty()) {
                    carried++;
                }
            }
            double rate = (double) carried / trials;
            assertTrue(rate <= c.pMax() + 0.02,
                    "carry rate " + rate + " exceeds pMax " + c.pMax() + " at level " + level);
            assertTrue(rate >= prev - 0.02,
                    "carry rate dropped with level: " + prev + " -> " + rate + " at level " + level);
            prev = rate;
        }
        assertTrue(prev > 0.20, "high-level S-tier carry rate should be near the cap, was " + prev);
    }

    @Test
    void belowMinLevelCarriesNothing() {
        BotPetConfig c = BotPetConfig.defaults();
        Random rng = new Random(7);
        for (int i = 0; i < 1000; i++) {
            assertTrue(BotPetAssigner.assign(9, BotTier.S, POOL, c, rng).isEmpty());
        }
    }

    @Test
    void countIsWithinOneToThreeAndDistinct() {
        BotPetConfig c = BotPetConfig.defaults();
        Random rng = new Random(99);
        int nonEmpty = 0;
        for (int i = 0; i < 20_000; i++) {
            List<PetSpec> specs = BotPetAssigner.assign(120, BotTier.S, POOL, c, rng);
            if (specs.isEmpty()) {
                continue; // the carry gate did not fire this draw
            }
            nonEmpty++;
            assertTrue(specs.size() >= 1 && specs.size() <= 3, "count " + specs.size());
            long distinct = specs.stream().map(PetSpec::itemId).distinct().count();
            assertEquals(specs.size(), distinct, "pet ids must be distinct");
        }
        assertTrue(nonEmpty > 0, "some draws must carry pets");
    }

    @Test
    void countDistributionShiftsRightWithStrength() {
        BotPetConfig c = BotPetConfig.defaults();
        // Compare the average count at low vs high strength directly via rollCount.
        Random rng = new Random(3);
        double lowAvg = avgCount(0.1, c, rng);
        double highAvg = avgCount(0.9, c, rng);
        assertTrue(highAvg > lowAvg, "stronger bots carry more pets: " + lowAvg + " vs " + highAvg);

        // Weak bots never carry more than one.
        rng = new Random(11);
        for (int i = 0; i < 5000; i++) {
            assertEquals(1, BotPetAssigner.rollCount(0.1, c, rng));
        }
    }

    @Test
    void petLevelStaysLow() {
        BotPetConfig c = BotPetConfig.defaults();
        assertEquals(1, BotPetAssigner.petLevel(0.0, c));
        assertTrue(BotPetAssigner.petLevel(1.0, c) <= c.petLevelMax());
        assertTrue(BotPetAssigner.petLevel(1.0, c) >= 1);
    }

    @Test
    void emptyPoolYieldsNoPets() {
        BotPetConfig c = BotPetConfig.defaults();
        Random rng = new Random(5);
        assertTrue(BotPetAssigner.assign(120, BotTier.S, List.of(), c, rng).isEmpty());
    }

    @Test
    void disabledConfigYieldsNoPets() {
        BotPetConfig disabled = BotPetConfig.fromMap(java.util.Map.of("enabled", false));
        assertTrue(!disabled.enabled(), "explicit enabled:false disables");
        Random rng = new Random(5);
        for (int i = 0; i < 1000; i++) {
            assertTrue(BotPetAssigner.assign(120, BotTier.S, POOL, disabled, rng).isEmpty());
        }
    }

    @Test
    void defaultsAreEnabled() {
        assertTrue(BotPetConfig.defaults().enabled(), "defaults are enabled");
    }

    private static double avgCount(double strength, BotPetConfig c, Random rng) {
        long total = 0;
        int trials = 200_000;
        for (int i = 0; i < trials; i++) {
            total += BotPetAssigner.rollCount(strength, c, rng);
        }
        return (double) total / trials;
    }
}
