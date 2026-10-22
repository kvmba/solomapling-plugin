package soloMapling.Environment;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two spawn batches on the same platform must never be handed the same pixel, and a batch must
 * get exactly the number of bots its points allow.
 *
 * <p>Both invariants meet in {@link PlatformPlacement#claimSpawnPoint}. It records the pick in
 * the shared per-platform pending set and reports it to the caller's working list; the batch loops
 * add the returned point to that same list themselves. Recording it on both sides made every batch
 * point count twice, so the occupancy scan saw a batch twice its real size and a platform handed
 * out roughly half the points it had room for. The claim was also published outside the monitor
 * that guards the shared set, so it could be written into a set a concurrent settle had already
 * unpublished - hidden from every later claimer, who could then be handed the same pixel.
 *
 * <p>Drives the package-private claim/settle pair directly: the batch entry points block on real
 * bot creation, which needs a running server.
 */
class PlatformSpawnClaimTest {

    // A SLOPED platform keeps the claim path off the ladder-nudge (which reads a live MapleMap).
    // Every recorded reference point is a legal spawn, 40px apart, so a batch of 8 always fits.
    private static final int MAP_ID = 910000000;

    private static final List<Point> REFERENCE_POINTS = List.of(
            new Point(100, 100), new Point(140, 100), new Point(180, 100), new Point(220, 100),
            new Point(260, 100), new Point(300, 100), new Point(340, 100), new Point(380, 100));

    private static Platform platform() {
        return new Platform(100, 380, 100, REFERENCE_POINTS, Platform.Type.SLOPED);
    }

    private static Point claim(String key, List<Point> claimed) {
        Point spot = PlatformPlacement.claimSpawnPoint(key, platform(), claimed, null, 0);
        if (spot != null) {
            claimed.add(spot); // the batch loops own this list and add the point themselves
        }
        return spot;
    }

    @Test
    void aBatchGetsOneDistinctPointPerBot() {
        String key = key("batch");
        List<Point> claimed = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                assertNotNull(claim(key, claimed), "eight reference points must seat bot " + i);
            }
            assertEquals(8, claimed.size());
            assertEquals(8, new HashSet<>(claimed).size(), "each bot must hold a distinct point");
        } finally {
            settleAll(key, claimed);
        }
    }

    @Test
    void aClaimStaysVisibleUntilItSettles() {
        String key = key("visibility");
        List<Point> claimed = new ArrayList<>();
        List<Point> later = new ArrayList<>();
        try {
            Point a = claim(key, claimed);
            assertNotNull(a);

            // A second batch on the same platform must not be handed a's pixel while it is in flight.
            for (int i = 0; i < 7; i++) {
                Point b = claim(key, later);
                assertNotNull(b);
                assertFalse(a.equals(b), "an in-flight claim must not be handed out again");
            }

            // Once a's spawn settles, its pixel is available again.
            PlatformPlacement.settleSpawnPoint(key, a);
            claimed.remove(a);
            assertTrue(reclaimFinds(key, a), "a settled point must become claimable again");
        } finally {
            settleAll(key, claimed);
            settleAll(key, later);
        }
    }

    /** The settled point is one of many free pixels, so try a few claims looking for it. */
    private static boolean reclaimFinds(String key, Point target) {
        List<Point> scratch = new ArrayList<>();
        try {
            for (int i = 0; i < 40; i++) {
                Point c = PlatformPlacement.claimSpawnPoint(key, platform(), scratch, null, 0);
                if (c == null) {
                    return false;
                }
                scratch.add(c);
                PlatformPlacement.settleSpawnPoint(key, c);
                scratch.remove(c);
                if (target.equals(c)) {
                    return true;
                }
            }
            return false;
        } finally {
            settleAll(key, scratch);
        }
    }

    /**
     * The regression the per-set monitor could not cover: every claimer publishes into the SAME
     * shared set for a platform, so concurrent batches must still produce distinct points. A
     * duplicate here means two bots were spawned onto one pixel.
     */
    @Test
    void concurrentBatchesNeverReuseAPixel() throws Exception {
        String key = key("concurrent");
        int threads = 4;
        int perThread = 2; // 8 distinct points, all held in flight at once
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Point> all = new ConcurrentLinkedQueue<>();
        try {
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    List<Point> own = new ArrayList<>();
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            Point spot = claim(key, own);
                            if (spot != null) {
                                all.add(spot);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "claimers must finish");

            assertEquals(threads * perThread, all.size(), "every claim must have found a free point");
            Set<Point> distinct = new HashSet<>(all);
            assertEquals(all.size(), distinct.size(),
                    "concurrent batches must never be handed the same pixel");
        } finally {
            pool.shutdownNow();
            settleAll(key, new ArrayList<>(all));
        }
    }

    private static String key(String suffix) {
        return MAP_ID + "/" + suffix;
    }

    private static void settleAll(String key, List<Point> points) {
        for (Point p : new ArrayList<>(points)) {
            PlatformPlacement.settleSpawnPoint(key, p);
        }
    }
}
