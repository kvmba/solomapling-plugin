package soloMapling.ArtificialPlayer.BotPartySystem;

import org.gms.client.Character;
import org.gms.extension.api.HostRuntime;
import org.gms.extension.event.PartyInviteEvent;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.CompanionBot;

import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.DebugUtilities.debugprint;

// Host-side party invites reach a bot only as a client packet it has no client to read, so this
// mirrors them into BotPartyQueue - the queue every recruit path drains (BotRecruitManager.pollInvites
// for the dialogue flow, BotPartyLogic.checkPartyQueue for OPQ). Without it a bot that just said
// "invite me!" never answers, and the player's invite sits until the coordinator times it out.
public final class BotPartyInviteBridge {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private BotPartyInviteBridge() {
    }

    // Subscribe once at plugin load (idempotent).
    public static void register(HostRuntime runtime) {
        if (runtime != null && REGISTERED.compareAndSet(false, true)) {
            runtime.events().subscribe(PartyInviteEvent.class, BotPartyInviteBridge::onPartyInvite);
        }
    }

    private static void onPartyInvite(PartyInviteEvent event) {
        Character invited = event.invited();
        Character inviter = event.inviter();
        if (invited == null || inviter == null) {
            return;
        }
        // Invites to real players keep going through their client; only plugin-owned bots need this.
        if (CharacterStorage.getBotById(invited.getId()) == null) {
            return;
        }
        debugprint("BotPartyInviteBridge: " + inviter.getName() + " invited bot " + invited.getName()
                + " to partyId=" + event.partyId());
        BotPartyQueue.getInstance().addPartyInvite(invited, inviter, event.partyId());
        if (CharacterStorage.getBotById(invited.getId()) instanceof CompanionBot companion) {
            companion.onPartyInvite(inviter, event.partyId());
        }
    }
}
