package soloMapling.ArtificialPlayer.BotMessagingSystem;

import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static soloMapling.BotLogger.log;
import static soloMapling.server.ExecutorServiceManager.getScheduledExecutorService;

public class QueueCleaner implements Runnable {

    private static final QueueCleaner cleaner = new QueueCleaner(MessageQueue.getInstance(), 10000);
    private final MessageQueue messageQueue;
    private final long expirationTime;
    private final ScheduledExecutorService scheduler = getScheduledExecutorService();

    public QueueCleaner(MessageQueue messageQueue, long expirationTime) {
        log("\n\nQueueCleaner OBJECT Created");
        this.messageQueue = messageQueue;
        this.expirationTime = expirationTime;
        this.scheduler.scheduleAtFixedRate(this, 0, 2, TimeUnit.SECONDS);
    }

    // Static method to access the singleton instance
    public static QueueCleaner getInstance() {
        log("Queue Cleaner getInstance");
        return cleaner;
    }

    @Override
    public void run() {
        long currentTime = System.currentTimeMillis();
        cleanQueue("primary", currentTime);
        cleanQueue("secondary", currentTime);
        cleanQueue("tertiary", currentTime);
    }

    private void cleanQueue(String queueName, long currentTime) {
        Collection<ChatMessage> messages = messageQueue.getQueue(queueName);
        messages.removeIf(message -> currentTime - message.getTimestamp() > expirationTime);
    }

    /*
     * Intentionally no stop() method, for the same reason as Dispatcher: the cleanup
     * task runs on the process-wide scheduled pool from ExecutorServiceManager, which
     * this class does not own. Shutting it down would stop every other subsystem's
     * periodic work (tick wheel, movement driver, grind sweep) along with this one.
     */
}
