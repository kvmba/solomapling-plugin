package soloMapling.plugin;

import org.gms.extension.event.CharacterChatEvent;
import org.gms.extension.event.CharacterMapEnteredEvent;
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
