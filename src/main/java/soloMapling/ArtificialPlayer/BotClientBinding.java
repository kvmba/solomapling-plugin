package soloMapling.ArtificialPlayer;

import org.gms.client.Character;
import org.gms.client.Client;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Binds an artificial character to its headless {@link Client} for the duration of
 * an operation that reaches engine code through {@code client.getPlayer()}.
 *
 * <p>Why this exists: bots never own a real session. Template-cloned bots (every
 * {@code BotGeneration.createBot} character) all share the single headless
 * {@code BotClient}, and only the bot→client edge is ever set
 * ({@code chr.setClient(botClient)}). The reverse edge is never established, so
 * {@code client.getPlayer()} is null. Engine paths that resolve the character
 * through the client then NPE — e.g. {@code Character.pickupItem} →
 * {@code InventoryManipulator.checkSpace} → {@code c.getPlayer().getInventory(...)}.
 *
 * <p>Persistent companions are the exception: {@code loadPersistentBot} gives each
 * of them a private {@code BotClient} and calls {@code setPlayer}, which is why
 * only template-cloned bots crash on pickup.
 *
 * <p>{@code pickupItem} is synchronous, so the temporary binding is sufficient for
 * the call — but it must be exclusive: the shared client carries one player slot,
 * and concurrent binds would let one bot read another's inventory. Callers hold the
 * client monitor for the whole operation, exactly like the companion controllers do.
 *
 * <p>Binding is also what makes the in-memory Character reachable from scripts and
 * packet paths that only have the client, so it restores correct behaviour beyond
 * the crash itself.
 */
public final class BotClientBinding {

    private BotClientBinding() {
    }

    /**
     * Runs {@code operation} with {@code chr} published on its client, restoring the
     * previous player afterwards. No-op-safe: if the character or its client is
     * missing the operation still runs, so behaviour degrades the same way it does
     * today instead of throwing from the binding helper.
     */
    public static <T> T withBoundPlayer(Character chr, Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        Client client = (chr == null) ? null : chr.getClient();
        if (client == null) {
            return operation.get();
        }
        synchronized (client) {
            Character previous = client.getPlayer();
            client.setPlayer(chr);
            try {
                return operation.get();
            } finally {
                client.setPlayer(previous);
            }
        }
    }

    /** Void-friendly overload for the common "run this engine call" case. */
    public static void runWithBoundPlayer(Character chr, Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        withBoundPlayer(chr, () -> {
            operation.run();
            return null;
        });
    }
}
