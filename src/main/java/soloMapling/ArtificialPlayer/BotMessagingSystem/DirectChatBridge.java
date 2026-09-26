package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;

import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

// Consumes the directed-channel events (CHAT_WHISPER/BUDDY/PARTY/GUILD/ALLIANCE) fed by
// HostGameplayEventBridge from the host CharacterDirectChatEvent and hands each to the addressed
// bot's own inbox.
//
// Why not the Dispatcher: that path finds the target by scanning the SENDER's map, which a whisper
// or party line (both cross-map by nature) never satisfies, and the shared primary/secondary queues
// are single-consumer - whichever bot polls first would steal a line meant for one specific bot.
public final class DirectChatBridge implements EventSubscriber {

    private static final DirectChatBridge INSTANCE = new DirectChatBridge();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private static final EventType[] TYPES = {
            EventType.CHAT_WHISPER, EventType.CHAT_BUDDY, EventType.CHAT_PARTY,
            EventType.CHAT_GUILD, EventType.CHAT_ALLIANCE
    };

    private DirectChatBridge() {
    }

    // Subscribe once at plugin load (idempotent).
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            for (EventType type : TYPES) {
                EventBus.getInstance().subscribe(type, INSTANCE);
            }
        }
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return event != null && event.getRecipient() != null;
    }

    // Runs synchronously on the sending player's packet thread (EventBus.publish neither offloads nor
    // catches), so keep it to one enqueue - never a packet send or a scan.
    @Override
    public void onEvent(GameEvent event) {
        try {
            Character recipient = event.getRecipient();
            Character sender = event.getMapleCharacter();
            String content = event.getMessage();
            if (recipient == null || sender == null || content == null || content.isBlank()
                    || isBot(sender)) {
                return;
            }
            BotSM bot = CharacterStorage.getBotById(recipient.getId());
            if (bot == null) {
                return;
            }
            bot.postDirectChat(new ChatMessage(sender, content, event.getChatType()));
            // A directed line reaches bots on other maps, where the macro tick is LOD-throttled
            // (an unobserved grinder drains its inbox on a 36-48s cadence, a grinding
            // TrainingBot on minutes) - the queued line would sit there for that whole stretch
            // before anyone read it. Pull the next tick forward so the inbox drains and the
            // reply goes back within a beat, the same way a map entry nudges a bot awake.
            // nudgeSoon no-ops itself on a stopped/trading bot, and the drain runs ahead of the
            // FSM on that tick, so the line is read as soon as the bot can act on it.
            bot.nudgeSoon(250L);
        } catch (Throwable ignored) {
        }
    }
}
