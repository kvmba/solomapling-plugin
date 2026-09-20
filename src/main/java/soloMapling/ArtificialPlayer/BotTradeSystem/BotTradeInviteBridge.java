package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.Character;
import org.gms.extension.api.HostRuntime;
import org.gms.extension.event.TradeInviteEvent;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;

import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.DebugUtilities.debugprint;

/**
 * Mirrors host {@link TradeInviteEvent} into {@link BotTradeQueue} so bot ticks can accept
 * without a real client answering the invite packet.
 *
 * <p>Only bot types that run a trade FSM ({@link BotSM#handlesTrades()}) drain that queue, so an
 * invite to any other type is answered here instead - a polite, randomly-delayed decline - or the
 * player's window would sit open until the host's invite coordinator times it out (~3 minutes).
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
        BotSM bot = CharacterStorage.getBotById(invited.getId());
        if (bot == null) {
            return;
        }
        // A trading type reads this queue on its own tick and runs the negotiation; leave it there.
        if (bot.handlesTrades()) {
            debugprint("BotTradeInviteBridge: " + inviter.getName() + " invited bot " + invited.getName());
            BotTradeQueue.getInstance().addTradeRequest(invited, inviter);
            return;
        }
        // Not a trader: answer for it (delayed decline + a "go to the FM" line) so the player is not
        // left waiting on a window this type will never touch.
        bot.declineTradePolitely(inviter);
    }
}
