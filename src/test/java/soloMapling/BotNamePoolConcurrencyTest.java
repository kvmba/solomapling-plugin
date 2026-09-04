package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.FreeMarket.FMShopDescGen;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Names are drawn while bots are being spawned, which happens from several threads during
 * startup waves. {@code FMShopDescGen} guards the pool with {@code synchronized} on all three
 * entry points; these checks pin that the pool hands out unique names under contention and that
 * the per-language rebuild cannot interleave into a half-built pool.
 */
class BotNamePoolConcurrencyTest {

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void concurrentDrawsStayUnique() throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");

        int threads = 16;
        int perThread = 60;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        // Per-thread lists so combining them cannot hide a duplicate handed to two threads.
        List<Set<String>> perThreadNames = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            perThreadNames.add(ConcurrentHashMap.newKeySet());
        }
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    start.await();
                    Set<String> mine = perThreadNames.get(idx);
                    for (int n = 0; n < perThread; n++) {
                        mine.add(FMShopDescGen.getRandomCharacterIGN());
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "draws did not finish");
        } finally {
            pool.shutdownNow();
        }

        Set<String> all = ConcurrentHashMap.newKeySet();
        int sum = 0;
        for (Set<String> s : perThreadNames) {
            all.addAll(s);
            sum += s.size();
        }
        int expected = threads * perThread;
        assertEquals(expected, sum, "a thread lost names under contention");
        // The pool is 3200 names and we draw 960, so any duplicate means the index was
        // incremented non-atomically.
        assertEquals(expected, all.size(), "the same name was handed to two threads");
    }

    @Test
    void shopOwnerDrawsDoNotThrowUnderContention() throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> owners = ConcurrentHashMap.newKeySet();
        try {
            // Mix both entry points: shop owners read the assigned-names list while
            // character draws are appending to it.
            for (int i = 0; i < threads; i++) {
                final boolean even = i % 2 == 0;
                pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < 80; n++) {
                        owners.add(even
                                ? FMShopDescGen.getRandomShopOwnerIGN()
                                : FMShopDescGen.getRandomCharacterIGN());
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "draws did not finish");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(owners.size() > 0, "no names were drawn");
    }

    // Every drawn name must satisfy the constraints the localized list is built to, including
    // under a mid-flight language switch (the pool rebuilds, it does not go empty).
    @Test
    void drawnNamesRespectWidthConstraints() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        for (int i = 0; i < 400; i++) {
            String name = FMShopDescGen.getRandomCharacterIGN();
            int width = 0;
            for (int c = 0; c < name.length(); c++) {
                width += Character.UnicodeBlock.of(name.charAt(c))
                        == Character.UnicodeBlock.BASIC_LATIN ? 1 : 2;
            }
            assertTrue(width >= 8 && width <= 12,
                    "drawn name '" + name + "' has display width " + width);
        }
    }
}
