package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.Character;
import org.gms.extension.api.HostRuntime;
import org.gms.extension.event.TradeInviteEvent;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;

import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.DebugUtilities.debugprint;

/**
 * Mirrors host {@link TradeInviteEvent} into {@link BotTradeQueue} so bot ticks can accept
 * without a real client answering the invite packet.
 */
public final class BotTradeInviteBridge {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private BotTradeInviteBridge() {
    }

    public static void register(HostRuntime runtime) {
        if (runtime != null && REGISTERED.compareAndSet(false, true)) {
            runtime.events().subscribe(TradeInviteEvent.class, BotTradeInviteBridge::onTradeInvite);
        }
    }

    private static void onTradeInvite(TradeInviteEvent event) {
        Character invited = event.invited();
        Character inviter = event.inviter();
        if (invited == null || inviter == null) {
            return;
        }
        if (CharacterStorage.getBotById(invited.getId()) == null) {
            return;
        }
        debugprint("BotTradeInviteBridge: " + inviter.getName() + " invited bot " + invited.getName());
        BotTradeQueue.getInstance().addTradeRequest(invited, inviter);
    }
}
