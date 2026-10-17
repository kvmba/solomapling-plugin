package soloMapling.ArtificialPlayer.BotMessagingSystem;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static soloMapling.BotLogger.log;

public class MessageQueue {
    public enum QueueType {
        PRIMARY("primary"),
        SECONDARY("secondary"),
        TERTIARY("tertiary");

        private final String name;

        QueueType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static QueueType fromString(String name) {
            for (QueueType type : values()) {
                if (type.name.equals(name)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Queue not found: " + name);
        }
    }

    private final Map<QueueType, BlockingQueue<ChatMessage>> queues;
    // Addressed follow-ups: one queue per bot. The shared "secondary"/"tertiary" queues are
    // single-consumer - whichever bot polls first takes the line - and every conversational type
    // polls them, so a reply meant for the dice bot was routinely swallowed by a SocialBot
    // mid-conversation (which then dropped it on the sender-id check) and the addressee never saw
    // it. Keys are character ids; entries are dropped when the bot goes away.
    private final Map<Integer, BlockingQueue<ChatMessage>> addressed = new ConcurrentHashMap<>();
    private static final int ADDRESSED_CAPACITY = 64;
    private static final MessageQueue messageQueue = new MessageQueue();

    static {
        QueueCleaner.getInstance();
        Dispatcher.getInstance();
    }

    private MessageQueue() {
        queues = new HashMap<>();
        queues.put(QueueType.PRIMARY, new LinkedBlockingQueue<>());
        queues.put(QueueType.SECONDARY, new LinkedBlockingQueue<>());
        queues.put(QueueType.TERTIARY, new LinkedBlockingQueue<>());
    }

    public static MessageQueue getInstance() {
        return messageQueue;
    }

    /**
     * Hand one line to a specific bot's own queue.
     *
     * <p>This is the addressed path (a player answering an interaction the bot itself opened). It
     * exists because {@code secondary} is single-consumer: with a dozen conversational types polling
     * it, the first poller takes the line even when it is not the addressee, and the intended bot
     * waits out its timeout. Bounded so a spammer cannot grow one bot's queue without limit.
     */
    public void addMessageForBot(int botCharacterId, ChatMessage message) {
        if (botCharacterId <= 0 || message == null) {
            return;
        }
        BlockingQueue<ChatMessage> queue = addressed.computeIfAbsent(botCharacterId,
                id -> new LinkedBlockingQueue<>(ADDRESSED_CAPACITY));
        queue.offer(message); // full = the bot is far behind anyway; drop the newest
    }

    /** Poll this bot's own queue (never blocks). Null when empty or never used. */
    public ChatMessage getAddressedMessage(int botCharacterId) {
        BlockingQueue<ChatMessage> queue = addressed.get(botCharacterId);
        return queue == null ? null : queue.poll();
    }

    /** Wait up to {@code timeout} for a line addressed to this bot; null on timeout. */
    public ChatMessage getAddressedMessage(int botCharacterId, long timeout, TimeUnit unit)
            throws InterruptedException {
        BlockingQueue<ChatMessage> queue = addressed.get(botCharacterId);
        return queue == null ? null : queue.poll(timeout, unit);
    }

    /** Drop a despawned bot's queue so the registry cannot grow for the life of the process. */
    public void forgetBot(int botCharacterId) {
        addressed.remove(botCharacterId);
    }

    public void addMessage(ChatMessage message) {
        addMessage(QueueType.SECONDARY, message);
    }

    public void addMessage(String queueName, ChatMessage message) {
        addMessage(QueueType.fromString(queueName), message);
    }

    public void addMessage(QueueType queueType, ChatMessage message) {
        queues.get(queueType).add(message);
    }

    public ChatMessage getMessage() throws InterruptedException {
        return getMessage(QueueType.SECONDARY);
    }

    public ChatMessage getMessage(String queueName) throws InterruptedException {
        return getMessage(QueueType.fromString(queueName));
    }

    public ChatMessage getMessage(QueueType queueType) throws InterruptedException {
        return queues.get(queueType).take();
    }

    public ChatMessage getMessageNonBlocking() {
        return getMessageNonBlocking(QueueType.SECONDARY);
    }

    public ChatMessage getMessageNonBlocking(String queueName) {
        return getMessageNonBlocking(QueueType.fromString(queueName));
    }

    public ChatMessage getMessageNonBlocking(QueueType queueType) {
        return queues.get(queueType).poll();
    }

    public ChatMessage getMessageWithTimeout(String queueName, long timeout, TimeUnit unit) throws InterruptedException {
        return getMessageWithTimeout(QueueType.fromString(queueName), timeout, unit);
    }

    public ChatMessage getMessageWithTimeout(QueueType queueType, long timeout, TimeUnit unit) throws InterruptedException {
        return queues.get(queueType).poll(timeout, unit);
    }

    public ChatMessage peekMessage() {
        return peekMessage(QueueType.PRIMARY);
    }

    public ChatMessage peekMessage(String queueName) {
        return peekMessage(QueueType.fromString(queueName));
    }

    public ChatMessage peekMessage(QueueType queueType) {
        return queues.get(queueType).peek();
    }

    public boolean removeMessageIfConditionMet(boolean condition) {
        return removeMessageIfConditionMet(QueueType.PRIMARY, condition);
    }

    public boolean removeMessageIfConditionMet(String queueName, boolean condition) {
        return removeMessageIfConditionMet(QueueType.fromString(queueName), condition);
    }

    public boolean removeMessageIfConditionMet(QueueType queueType, boolean condition) {
        BlockingQueue<ChatMessage> queue = queues.get(queueType);
        ChatMessage message = peekMessage(queueType);
        if (message != null && condition) {
            return queue.remove(message);
        } else {
            requeueMessage(queueType, queue.poll());
            System.out.println(" skipped irrelevant message: " + message.getContent());
            return false;
        }
    }

    public boolean requeueMessage(ChatMessage message) {
        return requeueMessage(QueueType.PRIMARY, message);
    }

    public boolean requeueMessage(String queueName, ChatMessage message) {
        return requeueMessage(QueueType.fromString(queueName), message);
    }

    public boolean requeueMessage(QueueType queueType, ChatMessage message) {
        return queues.get(queueType).offer(message);
    }

//    public void printAllMessages(String queueName) {
//        printAllMessages(QueueType.fromString(queueName));
//    }
//
//    public void printAllMessages(QueueType queueType) {
//        BlockingQueue<ChatMessage> queue = queues.get(queueType);
//        log("1----------------" + queueType.getName() + "--------------------1");
//        for (ChatMessage message : queue) {
//            log(queueType.getName() + ": " + message.getSender().getName() + ": " +
//                    message.getContent() + ", Timestamp: " + message.getTimestamp());
//        }
//        log("2----------------" + queueType.getName() + "--------------------2");
//    }

    public BlockingQueue<ChatMessage> getQueue(String queueName) {
        return getQueue(QueueType.fromString(queueName));
    }

    public BlockingQueue<ChatMessage> getQueue(QueueType queueType) {
        return queues.get(queueType);
    }
}
