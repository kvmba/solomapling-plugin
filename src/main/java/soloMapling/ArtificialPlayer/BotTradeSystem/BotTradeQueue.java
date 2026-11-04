package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin-owned pending trade invites (replaces host {@code PendingTradeInvites}).
 * Filled by {@link soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInviteBridge} from
 * {@code TradeInviteEvent}.
 *
 * <p>Keyed by character ID, not {@link Character}: the host's Character has no
 * {@code equals/hashCode}, so an object key made an entry invisible to any other instance of the
 * same character - a companion reloaded by {@code loadPersistentBot} would silently never see the
 * invite queued moments earlier - and nothing ever removed the stale entry (it kept the inviter's
 * Character alive for the life of the process).
 */
public final class BotTradeQueue {

    private static final BotTradeQueue INSTANCE = new BotTradeQueue();

    private final Map<Integer, Character> queues = new ConcurrentHashMap<>();

    private BotTradeQueue() {
    }

    public static BotTradeQueue getInstance() {
        return INSTANCE;
    }

    public void addTradeRequest(Character artificial, Character partner) {
        queues.putIfAbsent(artificial.getId(), partner);
    }

    public Character getTradeRequest(Character artificial) {
        return queues.get(artificial.getId());
    }

    public boolean hasPendingTrades(Character artificial) {
        return queues.containsKey(artificial.getId());
    }

    public void removeTradeRequest(Character artificial) {
        queues.remove(artificial.getId());
    }

    /** Drop a departed bot's queued invite (the queue would otherwise pin the inviter forever). */
    public void forgetBot(int characterId) {
        queues.remove(characterId);
    }
}
