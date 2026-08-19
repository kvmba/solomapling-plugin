package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin-owned pending trade invites (replaces host {@code PendingTradeInvites}).
 * Filled by {@link soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInviteBridge} from
 * {@code TradeInviteEvent}.
 */
public final class BotTradeQueue {

    private static final BotTradeQueue INSTANCE = new BotTradeQueue();

    private final Map<Character, Character> queues = new ConcurrentHashMap<>();

    private BotTradeQueue() {
    }

    public static BotTradeQueue getInstance() {
        return INSTANCE;
    }

    public void addTradeRequest(Character artificial, Character partner) {
        queues.putIfAbsent(artificial, partner);
    }

    public Character getTradeRequest(Character artificial) {
        return queues.get(artificial);
    }

    public boolean hasPendingTrades(Character artificial) {
        return queues.containsKey(artificial);
    }

    public void removeTradeRequest(Character artificial) {
        queues.remove(artificial);
    }
}
