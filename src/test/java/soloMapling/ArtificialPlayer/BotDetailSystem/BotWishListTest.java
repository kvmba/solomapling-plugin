package soloMapling.ArtificialPlayer.BotDetailSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic checks for the wishlist selection - no host, no game server. Locks the contracts:
 * the 10-entry cap, a level-scaled count, deterministic distinct picks drawn from the pool.
 */
class BotWishListTest {

    private static List<Integer> pool(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(10_000_000 + i);
        }
        return out;
    }

    @Test
    void belowLevelFloorTheWishlistIsEmpty() {
        for (int lv = 0; lv < 10; lv++) {
            assertTrue(BotWishList.select(1234, lv, pool(2000)).isEmpty(), "level " + lv);
        }
    }

    @Test
    void emptyPoolYieldsNothing() {
        assertTrue(BotWishList.select(1234, 50, List.of()).isEmpty());
    }

    @Test
    void neverExceedsTheClientCap() {
        List<Integer> pool = pool(BotWishList.MAX_WISHLIST * 4);
        for (int cid = 100; cid < 1_000; cid++) {
            for (int lv = 10; lv <= 200; lv += 7) {
                int size = BotWishList.select(cid, lv, pool).size();
                assertTrue(size <= BotWishList.MAX_WISHLIST, "cid " + cid + " lv " + lv + " -> " + size);
            }
        }
    }

    @Test
    void picksAreDeterministicAndDistinct() {
        List<Integer> big = pool(2000);
        for (int cid = 100; cid < 1_000; cid++) {
            List<Integer> a = BotWishList.select(cid, 50, big);
            List<Integer> b = BotWishList.select(cid, 50, big);
            assertEquals(a, b, "same cid must give same pick");
            assertEquals(a.size(), a.stream().distinct().count(), "no duplicates");
            assertTrue(big.containsAll(a), "all picks come from the pool");
        }
    }

    @Test
    void countGrowsWithLevel() {
        List<Integer> big = pool(2000);
        long low = 0;
        long high = 0;
        for (int cid = 100; cid < 1_100; cid++) {
            low += BotWishList.select(cid, 10, big).size();
            high += BotWishList.select(cid, 80, big).size();
        }
        assertTrue(high > low, "high-level mean " + high + " should exceed low-level mean " + low);
    }
}
