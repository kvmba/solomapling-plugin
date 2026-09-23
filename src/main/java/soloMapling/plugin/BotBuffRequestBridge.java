package soloMapling.plugin;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feeds real-player general chat (CHAT_GENERAL, published by {@link HostGameplayEventBridge}) into
 * {@link BotBuffRequestHandler}, so a player asking for a buff gets a nearby bot to grant one.
 * Bot chat never reaches this: {@code HostGameplayEventBridge} only publishes CHAT_GENERAL for real
 * senders, and the handler re-guards with {@code isBot}.
 *
 * <p>Runs synchronously on the chatting player's packet thread, so it only parses the line and
 * schedules the (delayed) grant - the handler never blocks here.
 */
public final class BotBuffRequestBridge implements EventSubscriber {

    private static final BotBuffRequestBridge INSTANCE = new BotBuffRequestBridge();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private BotBuffRequestBridge() {
    }

    // Subscribe once at plugin load (idempotent).
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            EventBus.getInstance().subscribe(EventType.CHAT_GENERAL, INSTANCE);
        }
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return event != null && event.getType() == EventType.CHAT_GENERAL;
    }

    @Override
    public void onEvent(GameEvent event) {
        try {
            Character sender = event.getMapleCharacter();
            String content = event.getMessage();
            if (sender == null || content == null) {
                return;
            }
            BotBuffRequestHandler.tryHandle(sender, content);
        } catch (Throwable ignored) {
            // A buff hook must never break the player's chat.
        }
    }
}
