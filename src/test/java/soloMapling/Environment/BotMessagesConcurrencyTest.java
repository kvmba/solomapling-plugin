package soloMapling.Environment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;



import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bots read messages from many threads (one per bot, plus the chat path). The pack is published as
 * a single immutable snapshot precisely so a reader can never observe a half-built state - and
 * never catch {@link BotMessages#invalidate()} mid-flight and read an empty map, which would
 * render a raw key ("party.declined") in front of a player.
 */
class BotMessagesConcurrencyTest {

    private static final String KEY = "party.declined";

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void firstLoadUnderConcurrentReadersNeverYieldsRawKey() throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotMessages.invalidate();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < 500; n++) {
                        seen.add(BotMessages.get(KEY, "X"));
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "readers did not finish");
        } finally {
            pool.shutdownNow();
        }

        // Every reader must agree, and none may ever see the raw key.
        assertEquals(1, seen.size(), "readers observed inconsistent values: " + seen);
        assertFalse(seen.contains(KEY), "a reader observed the raw key under concurrent load");
    }

    @Test
    void invalidateDuringReadsNeverYieldsRawKey() throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotMessages.get(KEY, "X"); // warm

        ExecutorService pool = Executors.newFixedThreadPool(8);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        try {
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    for (int n = 0; n < 300; n++) {
                        seen.add(BotMessages.get(KEY, "X"));
                    }
                });
            }
            // Race a language switch against the readers.
            for (int n = 0; n < 50; n++) {
                BotMessages.invalidate();
                seen.add(BotMessages.get(KEY, "X"));
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "readers did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertFalse(seen.contains(KEY),
                "invalidate() raced a read and produced the raw key: " + seen);
    }

    // A stampede of first-time readers must parse the pack once, not once per thread. Pinned so
    // that dropping the synchronized block in pack() shows up as a regression rather than as a
    // silent startup slowdown.
    @Test
    void concurrentFirstUseLoadsThePackOnce() throws Exception {
        // Measure the delta: earlier tests in this class also invalidate, so the absolute count is
        // cumulative across the JVM.
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotMessages.invalidate();
        int before = BotMessages.loadCount();

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    return BotMessages.get(KEY, "X");
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, BotMessages.loadCount() - before,
                "a stampede should parse the pack once, not once per thread");
    }
}
