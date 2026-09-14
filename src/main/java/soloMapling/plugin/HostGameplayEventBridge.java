package soloMapling.plugin;

import org.gms.extension.event.CharacterChatEvent;
import org.gms.extension.event.CharacterDirectChatEvent;
import org.gms.extension.event.CharacterMapEnteredEvent;
import org.gms.extension.event.ChatType;
import org.gms.extension.api.HostRuntime;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forwards host gameplay events into SoloMapling's internal {@link EventBus}
 * so existing bot subscribers keep working without the host importing soloMapling types.
 */
public final class HostGameplayEventBridge {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private HostGameplayEventBridge() {
    }

    public static void register(HostRuntime runtime) {
        if (runtime == null || !REGISTERED.compareAndSet(false, true)) {
            return;
        }
        runtime.events().subscribe(CharacterMapEnteredEvent.class, HostGameplayEventBridge::onMapEntered);
        runtime.events().subscribe(CharacterChatEvent.class, HostGameplayEventBridge::onChat);
        runtime.events().subscribe(CharacterDirectChatEvent.class, HostGameplayEventBridge::onDirectChat);
    }

    // A directed line (whisper / buddy / party / guild / alliance) addressed to a bot. The recipient
    // travels with the event so the consumer can hand it to that bot's own inbox - the map-wide
    // Dispatcher cannot reach a bot on another map or channel.
    private static void onDirectChat(CharacterDirectChatEvent event) {
        try {
            EventBus.getInstance().publish(new GameEvent(
                    event.sender(),
                    typeFor(event.type()),
                    event.message(),
                    null,
                    null,
                    event.recipient(),
                    event.type()));
        } catch (Throwable ignored) {
        }
    }

    private static EventType typeFor(ChatType type) {
        return switch (type) {
            case WHISPER -> EventType.CHAT_WHISPER;
            case BUDDY -> EventType.CHAT_BUDDY;
            case PARTY -> EventType.CHAT_PARTY;
            case GUILD -> EventType.CHAT_GUILD;
            case ALLIANCE -> EventType.CHAT_ALLIANCE;
        };
    }

    private static void onMapEntered(CharacterMapEnteredEvent event) {
        try {
            EventBus.getInstance().publish(new GameEvent(
                    event.character(),
                    EventType.MAP_ENTERED,
                    "Entered map " + event.mapId(),
                    null,
                    null));
        } catch (Throwable ignored) {
        }
    }

    private static void onChat(CharacterChatEvent event) {
        try {
            EventBus.getInstance().publish(new GameEvent(
                    event.character(),
                    EventType.CHAT_GENERAL,
                    event.message(),
                    null,
                    null));
        } catch (Throwable ignored) {
        }
    }
}
