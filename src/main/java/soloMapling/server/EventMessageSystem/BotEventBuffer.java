package soloMapling.server.EventMessageSystem;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/*
Wrapper around ConcurrentLinkedQueue<GameEvent>
Manages each bot's personal event queue. I.E. Personal Inbox for each bot.
Bots receive Events get stored here, waiting for processing
Push based
 */

public class BotEventBuffer {
    private final ConcurrentLinkedQueue<GameEvent> queue;
    // ConcurrentLinkedQueue.size() is an O(n) traversal and is not atomic against offer/poll, so
    // it is the wrong thing to gate a capacity on: two threads adding at once both saw
    // size() < maxSize and the cap was exceeded, while every add paid a full walk of the queue.
    //
    // The counter tracks the queue's content and is only ever adjusted by the thread that actually
    // changed the queue. It may transiently disagree with the queue's own size() when a concurrent
    // poll/offer is mid-flight, which is why nothing here asserts on it - it exists to make the
    // capacity check O(1) instead of O(n), and to report a cheap approximate size().
    private final AtomicInteger size = new AtomicInteger();
    private final int maxSize;

    public BotEventBuffer(int maxSize) {
        this.queue = new ConcurrentLinkedQueue<>();
        this.maxSize = maxSize;
    }

    public boolean add(GameEvent event) {
        // Evict the oldest while at capacity, then offer. The poll and the decrement are paired so
        // each dequeued element is accounted for exactly once; a poll that comes back empty means a
        // concurrent drain already made room, so the loop simply re-checks.
        while (size.get() >= maxSize) {
            if (queue.poll() == null) {
                break;
            }
            size.decrementAndGet();
        }
        if (queue.offer(event)) {
            size.incrementAndGet();
            return true;
        }
        return false;
    }

    public GameEvent poll() {
        GameEvent event = queue.poll();
        if (event != null) {
            size.decrementAndGet();
        }
        return event;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return size.get();
    }

    public void clear() {
        queue.clear();
        size.set(0);
    }
}
