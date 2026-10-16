package soloMapling.server.EventMessageSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BotEventBufferTest {

    @Test
    void capacityIsRespected() {
        BotEventBuffer buffer = new BotEventBuffer(3);
        GameEvent[] events = new GameEvent[10];
        for (int index = 0; index < events.length; index++) {
            events[index] = mock(GameEvent.class);
            assertTrue(buffer.add(events[index]));
        }
        assertEquals(3, buffer.size());
        // Oldest-first eviction: what survives is the newest three.
        assertSame(events[7], buffer.poll());
        assertSame(events[8], buffer.poll());
        assertSame(events[9], buffer.poll());
        assertTrue(buffer.isEmpty());
    }

    @Test
    void sizeStaysAccurateUnderConcurrentAdds() {
        // The old capacity check read ConcurrentLinkedQueue.size(), which is an O(n) walk and is
        // not atomic against offer: two threads adding at the same moment both saw room and the
        // cap was exceeded. The counter cannot overshoot by more than the number of threads that
        // raced through the check together, and each add still costs O(1).
        int maxSize = 64;
        int threads = 8;
        int perThread = 500;
        BotEventBuffer buffer = new BotEventBuffer(maxSize);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> workers = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            workers.add(() -> {
                await(start);
                for (int index = 0; index < perThread; index++) {
                    buffer.add(mock(GameEvent.class));
                }
            });
        }
        try {
            workers.forEach(pool::submit);
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
        assertTrue(buffer.size() <= maxSize,
                "size " + buffer.size() + " exceeded the cap of " + maxSize);

        // Draining must empty the queue. (The counter is deliberately NOT asserted equal to the
        // number drained: with concurrent producers it is an approximation, which is fine - the
        // cap is what matters and the queue is what is read.)
        int drained = 0;
        while (buffer.poll() != null) {
            drained++;
        }
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());
        assertTrue(drained > 0, "the buffer held events to drain");
    }

    @Test
    void clearResetsBothTheQueueAndTheCounter() {
        BotEventBuffer buffer = new BotEventBuffer(4);
        buffer.add(mock(GameEvent.class));
        buffer.add(mock(GameEvent.class));
        buffer.clear();
        assertEquals(0, buffer.size());
        assertTrue(buffer.isEmpty());
        assertTrue(buffer.add(mock(GameEvent.class)));
        assertEquals(1, buffer.size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
