package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block list is read and written from DIFFERENT bots' ticks: a bot adds its trade partner when
 * it declines, while the next bot's {@code BotTradeLogic.checkTradeQueue} calls {@code isBlocked}
 * before accepting. The two run on separate virtual threads.
 *
 * <p>An unsynchronised ArrayList let the sweep's {@code iterator.remove()} race the writer's
 * {@code add()}, throwing {@link java.util.ConcurrentModificationException} out of the whole bot
 * tick (BotTickService swallows it), which silently dropped the trade that tick was handling.
 */
class BotBlockListConcurrencyTest {

    @Test
    void concurrentAddsAndExpirySweepsNeverThrow() throws Exception {
        BotBlockList blockList = new BotBlockList();
        int writers = 4;
        int readers = 4;
        int iterations = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int w = 0; w < writers; w++) {
            final int writer = w;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        blockList.addToBlockList(writer, i, 0); // 0s: instantly expired
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        blockList.isBlocked(0, i);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "the workers must finish");

        assertTrue(failures.isEmpty(),
                "concurrent access must not throw: " + (failures.isEmpty() ? "" : failures.get(0)));
    }
}
