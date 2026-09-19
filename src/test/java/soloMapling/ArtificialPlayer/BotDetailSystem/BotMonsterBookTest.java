package soloMapling.ArtificialPlayer.BotDetailSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic checks for the monster-book preset - no WZ, no game server. Locks the count curve:
 * 0 below the level floor, bounded, monotone in level, and stable per (cid, level). The host owns
 * the book-level derivation now (see {@code MonsterBook.setCardCounts}).
 */
class BotMonsterBookTest {

    private static final int NORMAL_MIN = 4;
    private static final int NORMAL_MAX = 40;

    @Test
    void belowLevelFloorTheBookIsEmpty() {
        for (int lv = 0; lv < 10; lv++) {
            assertEquals(0, BotMonsterBook.pickCount(1234, lv, NORMAL_MIN, NORMAL_MAX, 0),
                    "level " + lv + " must show no cards");
        }
    }

    @Test
    void countIsBoundedAndNonNegativeAtEveryLevel() {
        for (int cid = 100; cid < 5_000; cid++) {
            for (int lv = 10; lv <= 200; lv++) {
                int n = BotMonsterBook.pickCount(cid, lv, NORMAL_MIN, NORMAL_MAX, 0);
                assertTrue(n >= 0 && n <= NORMAL_MAX, "cid " + cid + " lv " + lv + " -> " + n);
            }
        }
    }

    @Test
    void countRisesWithLevelForAnAverageBot() {
        // Averaged over ids the jitter cancels, so the mean must be monotone non-decreasing.
        int prev = -1;
        for (int lv = 10; lv <= 80; lv++) {
            long sum = 0;
            for (int cid = 100; cid < 3_100; cid++) {
                sum += BotMonsterBook.pickCount(cid, lv, NORMAL_MIN, NORMAL_MAX, 0);
            }
            int mean = (int) (sum / 3_000);
            assertTrue(mean >= prev, "mean dropped at level " + lv + ": " + prev + " -> " + mean);
            prev = mean;
        }
    }

    @Test
    void pickIsStableForTheSameIdAndLevel() {
        for (int cid = 100; cid < 2_000; cid++) {
            assertEquals(BotMonsterBook.pickCount(cid, 50, NORMAL_MIN, NORMAL_MAX, 0),
                    BotMonsterBook.pickCount(cid, 50, NORMAL_MIN, NORMAL_MAX, 0));
        }
    }
}
