package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.gms.client.Character;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;

import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

// The only producer of the "primary" queue the Dispatcher drains: the host publishes CHAT_GENERAL
// for every non-bot general chat line, this turns it into the ChatMessage the messaging stack
// expects. Without it no player line ever reaches a bot, so every name-call ("Tiger party?") is
// silently dropped and the whole dialogue stack (menus, party asks, trades) looks dead.
public final class PlayerChatBridge implements EventSubscriber {

    private static final PlayerChatBridge INSTANCE = new PlayerChatBridge();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private PlayerChatBridge() {
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

    // Runs synchronously on the chatting player's packet thread (EventBus.publish neither offloads
    // nor catches), so keep it to one enqueue and swallow everything - a bridge failure must never
    // break the player's chat.
    @Override
    public void onEvent(GameEvent event) {
        try {
            Character sender = event.getMapleCharacter();
            String content = event.getMessage();
            if (sender == null || content == null || content.isBlank() || isBot(sender)) {
                return;
            }
            MessageQueue.getInstance().addMessage("primary", new ChatMessage(sender, content));
        } catch (Throwable ignored) {
        }
    }
}
