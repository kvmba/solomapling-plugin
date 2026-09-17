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
 * monotonically to a {@value BotMedalAssigner#P_MAX} ceiling reached by {@link
 * BotMedalAssigner#L_CAP}, the plain (low-value) titles dominate at ~60% of everything
 * handed out, and the pick is always one of the supplied (already legal &amp; wearable)
 * medals.
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
    void wearChanceRisesMonotonicallyToCap() {
        double prev = -1.0;
        for (int lv = 1; lv <= 200; lv++) {
            double p = BotMedalAssigner.wearChance(lv);
            assertTrue(p >= prev, "wear chance dropped at level " + lv);
            assertTrue(p <= BotMedalAssigner.P_MAX + 1e-9, "wear chance exceeded cap at " + lv);
            prev = p;
        }
        // Reaches the ceiling at L_CAP and holds there.
        assertEquals(BotMedalAssigner.P_MAX, BotMedalAssigner.wearChance(BotMedalAssigner.L_CAP), 1e-9);
        assertEquals(BotMedalAssigner.P_MAX, BotMedalAssigner.wearChance(200), 1e-9);
    }

    @Test
    void empiricalShareMatchesCurveAndCapsAtCap() {
        Random rng = new Random(7);
        // At the cap level: about 65%.
        double at20 = empiricalShare(20, rng, 200_000);
        assertTrue(at20 > 0.61 && at20 < 0.69, "lvl20 share was " + at20);
        // Halfway up the slope (lv15): about half the cap.
        double at15 = empiricalShare(15, rng, 200_000);
        assertTrue(at15 > 0.28 && at15 < 0.37, "lvl15 share was " + at15);
    }

    @Test
    void lowValueTierDominatesAtAboutSixtyPercent() {
        Random rng = new Random(3);
        // A pool shaped like the live one: many low-value, fewer mid, few high.
        List<Medal> pool = mixedPool();

        int low = 0, mid = 0, high = 0;
        for (int i = 0; i < 200_000; i++) {
            Medal m = BotMedalAssigner.pickFrom(pool, 50, rng);
            if (m.value < BotMedalPool.VALUE_LOW_MAX) low++;
            else if (m.value < BotMedalPool.VALUE_MID_MAX) mid++;
            else high++;
        }
        double lowShare = low / 200_000.0;
        assertTrue(lowShare > 0.55 && lowShare < 0.65, "low-value share was " + lowShare);
        assertTrue(mid > high, "mid tier should outdraw high tier");
    }

    @Test
    void missingTierRenormalisesWithoutCrash() {
        Random rng = new Random(9);
        // Only high-value medals: the assigner must still pick one.
        List<Medal> onlyHigh = List.of(medal(1142165, 0, 22), medal(1142151, 50, 20));
        assertNotNull(BotMedalAssigner.pickFrom(onlyHigh, 100, rng));
        // Only low-value medals.
        List<Medal> onlyLow = List.of(medal(1142000, 0, 5), medal(1142004, 0, 6));
        for (int i = 0; i < 100; i++) {
            assertTrue(BotMedalAssigner.pickFrom(onlyLow, 50, rng).value < BotMedalPool.VALUE_LOW_MAX);
        }
    }

    @Test
    void pickOnlyReturnsEligibleMedals() {
        Random rng = new Random(11);
        List<Medal> pool = mixedPool();
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

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Medal medal(int id, int reqLevel, int value) {
        return new Medal(id, new int[]{reqLevel, 0, 0, 0, 0, 0, 0}, value, "m" + id);
    }

    /** 10 low / 6 mid / 4 high — a rough stand-in for the live 31/34/15 pool shape. */
    private static List<Medal> mixedPool() {
        List<Medal> pool = new ArrayList<>();
        int id = 1142000;
        for (int i = 0; i < 10; i++) pool.add(medal(id++, 0, 4));   // low
        for (int i = 0; i < 6; i++) pool.add(medal(id++, 0, 14));   // mid
        for (int i = 0; i < 4; i++) pool.add(medal(id++, 0, 30));   // high
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
