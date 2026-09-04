package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bot spawn rate limit without starting the game server.
 *
 * <p>The throttle is what keeps startup from pinning the CPU: it must let N
 * through per second, no more, and it must not serialize callers into a
 * multi-second queue when a batch larger than the window arrives.
 */
class BotSpawnThrottleTest {

    @AfterEach
    void restoreConfiguredLimit() {
        BotSpawnThrottle.setLimitForTest(null);
    }

    @Test
    void unlimitedLimitNeverBlocks() {
        BotSpawnThrottle.setLimitForTest(BotSpawnThrottle.UNLIMITED);
        assertFalse(BotSpawnThrottle.enabled());
        assertEquals(Integer.MAX_VALUE, BotSpawnThrottle.remaining());

        // 200 acquires would take 20s at 10/s; unlimited must be effectively instant.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 200; i++) {
                BotSpawnThrottle.acquire();
            }
        });
    }

    @Test
    void negativeLimitIsTreatedAsUnlimited() {
        BotSpawnThrottle.setLimitForTest(-5);
        assertFalse(BotSpawnThrottle.enabled());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 100; i++) {
                BotSpawnThrottle.acquire();
            }
        });
    }

    @Test
    void firstWindowGrantsExactlyTheLimit() {
        BotSpawnThrottle.setLimitForTest(10);
        assertTrue(BotSpawnThrottle.enabled());
        assertEquals(10, BotSpawnThrottle.remaining());

        // The whole window must be available up front - a wave of 10 spawns as
        // fast as the machine allows rather than one per 100ms.
        assertTimeoutPreemptively(java.time.Duration.ofMillis(500), () -> {
            for (int i = 0; i < 10; i++) {
                BotSpawnThrottle.acquire();
            }
        });
        assertEquals(0, BotSpawnThrottle.remaining());
    }

    @Test
    void eleventhAcquireWaitsForTheNextWindow() {
        BotSpawnThrottle.setLimitForTest(10);
        for (int i = 0; i < 10; i++) {
            BotSpawnThrottle.acquire();
        }
        assertEquals(0, BotSpawnThrottle.remaining());

        long start = System.currentTimeMillis();
        BotSpawnThrottle.acquire();
        long elapsed = System.currentTimeMillis() - start;

        // Must have blocked for roughly the rest of the window, and must not
        // have waited two windows.
        assertTrue(elapsed >= 500, "expected to block for the remainder of the window, took " + elapsed + "ms");
        assertTrue(elapsed <= 1900, "waited too long: " + elapsed + "ms");
        assertEquals(9, BotSpawnThrottle.remaining(), "a fresh window should follow the blocking acquire");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentCallersRespectTheRate() throws Exception {
        final int limit = 20;
        final int threads = 8;
        final int perThread = 5; // 40 total = exactly 2 windows at 20/s
        BotSpawnThrottle.setLimitForTest(limit);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger completed = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();

        long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        BotSpawnThrottle.acquire();
                        completed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "throttle deadlocked under concurrency");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(threads * perThread, completed.get());
        // 40 permits at 20/s needs at least one window boundary (~1s). Allow
        // slack for scheduling, but catch a throttle that let everything through.
        assertTrue(elapsedMs >= 800,
                "40 bots at 20/s must span a window boundary, took " + elapsedMs + "ms");
        assertTrue(elapsedMs <= 5000,
                "40 bots at 20/s should finish well under 5s, took " + elapsedMs + "ms");

        for (Thread worker : workers) {
            worker.join(1000);
        }
    }

    @Test
    void estimatedWaitSecondsReflectsTheConfiguredRate() {
        BotSpawnThrottle.setLimitForTest(10);
        assertEquals(0.0, BotSpawnThrottle.estimatedWaitSeconds(0), 1e-9);

        // Empty window: the first 10 are free now, the remaining 90 need 9 more
        // windows, so the 100th bot lands ~9s out (not 10s).
        double estimate = BotSpawnThrottle.estimatedWaitSeconds(100);
        assertEquals(9.0, estimate, 1e-9);

        // A batch that fits in the current window waits nothing.
        assertEquals(0.0, BotSpawnThrottle.estimatedWaitSeconds(10), 1e-9);
    }

    @Test
    void unlimitedHasNoEstimatedWait() {
        BotSpawnThrottle.setLimitForTest(0);
        assertEquals(0.0, BotSpawnThrottle.estimatedWaitSeconds(1000), 1e-9);
    }

    @Test
    void resetReopensTheWindow() {
        BotSpawnThrottle.setLimitForTest(3);
        BotSpawnThrottle.acquire();
        BotSpawnThrottle.acquire();
        BotSpawnThrottle.acquire();
        assertEquals(0, BotSpawnThrottle.remaining());

        BotSpawnThrottle.reset();
        assertEquals(3, BotSpawnThrottle.remaining());
    }

    @Test
    void acquireZeroOrNegativeIsANoOp() {
        BotSpawnThrottle.setLimitForTest(1);
        BotSpawnThrottle.acquire();           // consume the only permit
        assertEquals(0, BotSpawnThrottle.remaining());

        // A zero-count batch must not consume a permit or block on the next window.
        assertTimeoutPreemptively(java.time.Duration.ofMillis(200), () -> {
            BotSpawnThrottle.acquire(0);
            BotSpawnThrottle.acquire(-3);
        });
        assertEquals(0, BotSpawnThrottle.remaining());
    }
}
