package soloMapling.ArtificialPlayer.BotDetailSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic checks for the medal-collection selection - no WZ, no game server. Locks the
 * contracts: a level-scaled count, deterministic picks, and the worn medal's quest always
 * present when it is eligible.
 */
class BotMedalBookTest {

    /** A stand-in eligible table: {questId, medalId} pairs like the loaded 29xxx table. */
    private static List<int[]> table(int n) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new int[]{29000 + i, 1142000 + i});
        }
        return out;
    }

    @Test
    void belowLevelFloorNothingIsCollected() {
        for (int lv = 0; lv < 10; lv++) {
            assertTrue(BotMedalBook.select(1234, lv, table(20), 0).isEmpty(), "level " + lv);
        }
    }

    @Test
    void emptyTableYieldsNothing() {
        assertTrue(BotMedalBook.select(1234, 50, List.of(), 0).isEmpty());
    }

    @Test
    void selectionIsDeterministicAndDistinct() {
        List<int[]> pool = table(22);
        for (int cid = 100; cid < 1_000; cid++) {
            List<Integer> a = BotMedalBook.select(cid, 50, pool, 0);
            List<Integer> b = BotMedalBook.select(cid, 50, pool, 0);
            assertEquals(a, b, "same cid must give same pick");
            assertEquals(a.size(), a.stream().distinct().count(), "no duplicates");
        }
    }

    @Test
    void wornMedalQuestIsAlwaysIncludedWhenEligible() {
        List<int[]> pool = table(22);
        int wornMedal = 1142007; // -> quest 29007
        for (int cid = 100; cid < 2_000; cid++) {
            List<Integer> chosen = BotMedalBook.select(cid, 40, pool, wornMedal);
            assertTrue(chosen.contains(29007), "cid " + cid + " must include the worn medal's quest");
        }
    }

    @Test
    void countIsBoundedByPoolAndGrowsWithLevel() {
        List<int[]> pool = table(20);
        for (int cid = 100; cid < 1_000; cid++) {
            for (int lv = 10; lv <= 200; lv++) {
                List<Integer> chosen = BotMedalBook.select(cid, lv, pool, 0);
                assertTrue(chosen.size() >= 1 && chosen.size() <= pool.size(),
                        "cid " + cid + " lv " + lv + " -> " + chosen.size());
            }
        }
        // A high-level bot collects more than a level-10 one, on average.
        long low = 0;
        long high = 0;
        for (int cid = 100; cid < 1_100; cid++) {
            low += BotMedalBook.select(cid, 10, pool, 0).size();
            high += BotMedalBook.select(cid, 80, pool, 0).size();
        }
        assertTrue(high > low, "high-level mean " + high + " should exceed low-level mean " + low);
    }

    @Test
    void allChoicesComeFromTheTable() {
        List<int[]> pool = table(22);
        List<Integer> ids = new ArrayList<>();
        for (int[] p : pool) {
            ids.add(p[0]);
        }
        for (int cid = 100; cid < 1_000; cid++) {
            for (int q : BotMedalBook.select(cid, 60, pool, 0)) {
                assertTrue(ids.contains(q), "unexpected quest " + q);
            }
        }
        assertFalse(ids.isEmpty());
    }
}
