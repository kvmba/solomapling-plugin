package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency invariants of {@link BotSpotClaims} after it moved from one process-wide monitor to
 * one monitor per map.
 *
 * <p>The per-map split is only sound if it preserves exactly the two guarantees the single lock
 * gave: a spot never admits more holders than its capacity, and two DIFFERENT bots are never handed
 * the same slot index. Both are asserted here under real contention (all threads hammering the same
 * map+spot at once), plus independence across maps and clean release.
 *
 * <p>{@code BotSpotClaimsTest} covers the single-threaded contract; this covers what the lock
 * change could actually break.
 */
class BotSpotClaimsConcurrencyTest {

    private static final int THREADS = 16;

    @Test
    void capacityIsNeverExceededAndNoSlotIsHandedToTwoBots() throws Exception {
        int cap = 4;
        int mapId = 910_001;
        int spotId = 3;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allClaimed = new CountDownLatch(THREADS);
        CountDownLatch release = new CountDownLatch(1);
        AtomicIntegerArray slotOf = new AtomicIntegerArray(THREADS);
        AtomicInteger granted = new AtomicInteger();

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            final int botId = 20_000 + i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    int slot = BotSpotClaims.claim(mapId, spotId, cap, botId);
                    slotOf.set(idx, slot);
                    if (slot >= 0) {
                        granted.incrementAndGet();
                    }
                    allClaimed.countDown();
                    // Hold every grant simultaneously so the readers below see the true peak,
                    // then release this bot's claim exactly once.
                    release.await();
                    if (slot >= 0) {
                        BotSpotClaims.release(mapId, spotId, botId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }

        start.countDown();
        assertTrue(allClaimed.await(10, TimeUnit.SECONDS), "all claimants should finish promptly");

        // Peak window: capacity must be respected, and each grant must be a distinct slot.
        assertEquals(cap, granted.get(), "exactly capacity grants under contention, never more");
        assertEquals(cap, BotSpotClaims.holders(mapId, spotId), "registry agrees with the grants");
        Set<Integer> distinct = new HashSet<>();
        for (int i = 0; i < THREADS; i++) {
            int slot = slotOf.get(i);
            if (slot >= 0) {
                assertTrue(distinct.add(slot), "slot " + slot + " was handed to two different bots");
            }
        }

        release.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(0, BotSpotClaims.holders(mapId, spotId), "every claim released");
    }

    @Test
    void claimsOnDifferentMapsDoNotInterfere() throws Exception {
        // Two maps, each with its own capacity. A per-map lock must let both fill to their own cap
        // regardless of what the other map is doing, and each bot must get its map's slot space.
        int cap = 3;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger okA = new AtomicInteger();
        AtomicInteger okB = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int m = 0; m < 2; m++) {
            final int mapId = 920_000 + m;
            final AtomicInteger ok = m == 0 ? okA : okB;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < cap + 2; i++) {
                    if (BotSpotClaims.claim(mapId, 1, cap, 30_000 + i) >= 0) {
                        ok.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(cap, okA.get(), "map A fills to its own capacity");
        assertEquals(cap, okB.get(), "map B fills to its own capacity");
        for (int m = 0; m < 2; m++) {
            for (int i = 0; i < cap + 2; i++) {
                BotSpotClaims.release(920_000 + m, 1, 30_000 + i);
            }
        }
    }

    @Test
    void slotAllocationOrderMatchesTheOldGlobalLockSemantics() {
        // The old implementation handed out the LOWEST free slot on every claim. A per-map lock
        // must not change that: bots that claim in a fixed order get 0,1,2..., and after an
        // interior release the freed index is reused before a higher one is opened.
        int mapId = 940_001;
        int spotId = 2;
        int cap = 5;
        assertEquals(0, BotSpotClaims.claim(mapId, spotId, cap, 50_000));
        assertEquals(1, BotSpotClaims.claim(mapId, spotId, cap, 50_001));
        assertEquals(2, BotSpotClaims.claim(mapId, spotId, cap, 50_002));
        BotSpotClaims.release(mapId, spotId, 50_001);           // free index 1
        assertEquals(1, BotSpotClaims.claim(mapId, spotId, cap, 50_003),
                "the lowest freed index is reused, exactly like the old global-lock scan");
        assertEquals(3, BotSpotClaims.claim(mapId, spotId, cap, 50_004));
        assertEquals(4, BotSpotClaims.claim(mapId, spotId, cap, 50_005));
        assertEquals(-1, BotSpotClaims.claim(mapId, spotId, cap, 50_006), "full at capacity");
        for (int id = 50_000; id <= 50_005; id++) {
            BotSpotClaims.release(mapId, spotId, id);
        }
        assertEquals(0, BotSpotClaims.holders(mapId, spotId));
    }

    @Test
    void repeatedClaimReleaseIsStable() {
        // A stale map entry must never resurrect a released claim, and holders() must not create
        // map entries as a side effect of being read.
        int mapId = 930_001;
        assertEquals(0, BotSpotClaims.holders(mapId, 1), "reading an untouched map is zero");
        assertEquals(0, BotSpotClaims.holders(mapId, 1), "and stays zero on re-read");
        for (int round = 0; round < 50; round++) {
            assertEquals(0, BotSpotClaims.claim(mapId, 1, 2, 40_000));
            assertEquals(1, BotSpotClaims.holders(mapId, 1));
            BotSpotClaims.release(mapId, 1, 40_000);
            assertEquals(0, BotSpotClaims.holders(mapId, 1));
        }
    }
}
