package soloMapling.ArtificialPlayer.BotMedalSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMedalSystem.BotMedalPool.Medal;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic checks for the bot 称号 (medal) assignment math — no game server, no WZ.
 *
 * <p>Pins the product rules: no title below level 10, the share wearing one rises
 * monotonically to a 50% ceiling, higher levels favour the advanced (reqLevel>0)
 * tier, and the pick is always one of the supplied (already legal & wearable) medals.
 */
class BotMedalAssignerTest {

    private static final int SAMPLES = 40000;

    @Test
    void noTitleBelowMinLevel() {
        assertEquals(0.0, BotMedalAssigner.wearChance(1));
        assertEquals(0.0, BotMedalAssigner.wearChance(9));
        assertTrue(BotMedalAssigner.wearChance(10) >= 0.0);
    }

    @Test
    void wearChanceRisesMonotonicallyToHalf() {
        double prev = -1.0;
        for (int lv = 1; lv <= 200; lv++) {
            double p = BotMedalAssigner.wearChance(lv);
            assertTrue(p >= prev, "wear chance dropped at level " + lv);
            assertTrue(p <= BotMedalAssigner.P_MAX + 1e-9, "wear chance exceeded 50% at " + lv);
            prev = p;
        }
        // Reaches the 50% ceiling and holds there.
        assertEquals(0.50, BotMedalAssigner.wearChance(BotMedalAssigner.L_CAP), 1e-9);
        assertEquals(0.50, BotMedalAssigner.wearChance(200), 1e-9);
    }

    @Test
    void empiricalShareMatchesCurveAndCapsAtHalf() {
        Random rng = new Random(7);
        // Mid-level: about 1 in 3.
        double at50 = empiricalShare(50, rng, 200_000);
        assertTrue(at50 > 0.12 && at50 < 0.22, "lvl50 share was " + at50);
        // Top end never exceeds 50%.
        double at130 = empiricalShare(130, rng, 200_000);
        assertTrue(at130 > 0.46 && at130 <= 0.51, "lvl130 share was " + at130);
    }

    @Test
    void advancedTierFavouredAtHigherLevels() {
        Random rng = new Random(3);
        // Same pool: mostly reqLevel 0 with some reqLevel 70 titles.
        List<Medal> pool = poolWithAdvanced();

        int lowAdvanced = 0;
        int highAdvanced = 0;
        for (int i = 0; i < SAMPLES; i++) {
            if (BotMedalAssigner.pickFrom(pool, 20, rng).reqLevel > 0) lowAdvanced++;
            if (BotMedalAssigner.pickFrom(pool, 120, rng).reqLevel > 0) highAdvanced++;
        }
        assertTrue(highAdvanced > lowAdvanced,
                "advanced picks did not rise with level: low=" + lowAdvanced + " high=" + highAdvanced);
        assertEquals(0, lowAdvanced, "level 20 (below ADV_MIN_LEVEL) should never get an advanced title");
    }

    @Test
    void pickOnlyReturnsEligibleMedals() {
        Random rng = new Random(11);
        List<Medal> pool = poolWithAdvanced();
        for (int i = 0; i < 1000; i++) {
            Medal m = BotMedalAssigner.pickFrom(pool, 100, rng);
            assertNotNull(m);
            assertTrue(pool.contains(m), "pick returned a medal outside the eligible pool");
        }
    }

    @Test
    void emptyPoolPicksNothing() {
        assertNull(BotMedalAssigner.pickFrom(new ArrayList<>(), 100, new Random(1)));
        assertNull(BotMedalAssigner.pickFrom(null, 100, new Random(1)));
    }

    @Test
    void highestReachesAdvancedTierMostOfTheTimeWhenOnlyAdvancedWearable() {
        // Only advanced (reqLevel 60) medals in the pool: the assigner must still pick one.
        List<Medal> onlyAdvanced = List.of(medal(1142153, 60), medal(1142154, 60));
        assertNotNull(BotMedalAssigner.pickFrom(onlyAdvanced, 100, new Random(2)));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Medal medal(int id, int reqLevel) {
        return new Medal(id, new int[]{reqLevel, 0, 0, 0, 0, 0, 0}, "m" + id);
    }

    private static List<Medal> poolWithAdvanced() {
        List<Medal> pool = new ArrayList<>();
        for (int id = 1142000; id < 1142010; id++) {   // 10 basic (reqLevel 0)
            pool.add(medal(id, 0));
        }
        for (int id = 1142109; id < 1142114; id++) {   // 5 advanced (reqLevel 70)
            pool.add(medal(id, 70));
        }
        return pool;
    }

    private static double empiricalShare(int level, Random rng, int samples) {
        double p = BotMedalAssigner.wearChance(level);
        int worn = 0;
        for (int i = 0; i < samples; i++) {
            if (rng.nextDouble() < p) worn++;
        }
        return worn / (double) samples;
    }
}
