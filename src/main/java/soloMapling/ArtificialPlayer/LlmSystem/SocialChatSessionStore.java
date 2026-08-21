package soloMapling.ArtificialPlayer.LlmSystem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory sliding window of user/assistant turns for one bot↔player conversation.
 */
public final class SocialChatSessionStore {

    private SocialChatSessionStore() {
    }

    public record Turn(String role, String content) {
    }

    private static final ConcurrentMap<String, Deque<Turn>> SESSIONS = new ConcurrentHashMap<>();

    public static void addUser(int botId, int playerId, String content) {
        addTurn(botId, playerId, "user", content);
    }

    public static void addAssistant(int botId, int playerId, String content) {
        addTurn(botId, playerId, "assistant", content);
    }

    public static List<LlmMessage> toMessages(int botId, int playerId, int maxTurns) {
        Deque<Turn> turns = SESSIONS.get(key(botId, playerId));
        if (turns == null || turns.isEmpty() || maxTurns <= 0) {
            return List.of();
        }
        List<Turn> slice = new ArrayList<>(turns);
        int from = Math.max(0, slice.size() - maxTurns);
        List<LlmMessage> messages = new ArrayList<>();
        for (Turn turn : slice.subList(from, slice.size())) {
            if ("assistant".equals(turn.role())) {
                messages.add(LlmMessage.assistant(turn.content()));
            } else {
                messages.add(LlmMessage.user(turn.content()));
            }
        }
        return messages;
    }

    public static void clear(int botId, int playerId) {
        SESSIONS.remove(key(botId, playerId));
    }

    private static void addTurn(int botId, int playerId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Deque<Turn> deque = SESSIONS.computeIfAbsent(key(botId, playerId), k -> new ArrayDeque<>());
        deque.addLast(new Turn(role, content.trim()));
        int cap = Math.max(2, SocialLlmConfig.historyTurns() * 2);
        while (deque.size() > cap) {
            deque.removeFirst();
        }
    }

    private static String key(int botId, int playerId) {
        return botId + ":" + playerId;
    }
}
