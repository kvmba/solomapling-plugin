package soloMapling.ArtificialPlayer.BotPartySystem;

import org.gms.client.Character;
import org.gms.net.server.coordinator.world.InviteCoordinator;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteResult;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteResultType;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteType;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.net.server.world.PartyOperation;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

import soloMapling.Environment.BotMessages;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static soloMapling.DebugUtilities.debugprint;

public class BotPartyCommands {

    // Guarded by the invite queue monitor. Party IDs are local to a world.
    private static final Set<Integer> JOINING_BOTS = new HashSet<>();
    private static final Map<PartyKey, PartyJoin> PARTY_JOINS = new HashMap<>();

    private record PartyKey(int world, int partyId) {
    }

    private static final class PartyJoin {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    public static boolean botMakeParty(Character fakechar) {
        if (fakechar.getParty() != null) {
            debugprint("botMakeParty: bot already in a party, skipping.");
            return false;
        }
        boolean created = Party.createParty(fakechar, false);
        debugprint("botMakeParty: created=" + created);
        return created;
    }

    // Server-side leave: use the actual avatar, not a shared BotClient's current player.
    // Event-instance membership is real and must observe the same callbacks as a player leave.
    public static void botLeaveParty(Character fakechar) {
        Party party = fakechar.getParty();
        if (party == null) {
            debugprint("botLeaveParty: no party, skipping.");
            return;
        }

        PartyCharacter botPC = fakechar.getMPC();
        if (botPC == null) {
            botPC = new PartyCharacter(fakechar);
        }

        World world = fakechar.getWorldServer();
        int partyId = party.getId();

        if (botPC.getId() == party.getLeaderId()) {
            world.removeMapPartyMembers(partyId);
            world.updateParty(partyId, PartyOperation.DISBAND, botPC);
            var eim = fakechar.getEventInstance();
            if (eim != null) eim.disbandParty();
            debugprint("botLeaveParty: bot=" + fakechar.getName() + " disbanded party " + partyId);
        } else {
            MapleMap map = fakechar.getMap();
            if (map != null) {
                map.removePartyMember(fakechar, partyId);
            }
            world.updateParty(partyId, PartyOperation.LEAVE, botPC);
            var eim = fakechar.getEventInstance();
            if (eim != null) eim.leftParty(fakechar);
            debugprint("botLeaveParty: bot=" + fakechar.getName() + " left party " + partyId);
        }

        fakechar.setParty(null);
    }

    public static boolean botAcceptPartyInvite(Character fakechar) {
        return botAcceptPartyInvite(fakechar, -1);
    }

    /**
     * Validate and consume under the queue lock, but do not hold it while the host
     * takes map locks or sends packets.
     */
    public static boolean botAcceptPartyInvite(Character fakechar, int expectedInviterId) {
        BotPartyQueue queue = BotPartyQueue.getInstance();
        int botId = fakechar.getId();
        Character inviter;
        int partyId;
        PartyKey partyKey;
        PartyJoin join;
        synchronized (queue) {
            if (JOINING_BOTS.contains(botId)) {
                return false;
            }
            BotPartyQueue.PartyInviteEntry entry = queue.getPartyInvite(fakechar);
            if (entry == null) {
                debugprint("botAcceptPartyInvite: no pending invite for " + fakechar.getName());
                return false;
            }
            inviter = entry.getInviter();
            if (expectedInviterId > 0
                    && (inviter == null || inviter.getId() != expectedInviterId)) {
                debugprint("botAcceptPartyInvite: pending inviter does not match expected id="
                        + expectedInviterId);
                return false;
            }

            partyId = entry.getPartyId();
            InviteResult res = InviteCoordinator.answerInvite(
                    InviteType.PARTY, botId, partyId, true);
            queue.removePartyInvite(fakechar);
            if (res.result != InviteResultType.ACCEPTED) {
                debugprint("botAcceptPartyInvite: invite expired/invalid, result=" + res.result);
                return false;
            }
            // Serialize bot joins to this party: the host checks capacity before adding the member.
            partyKey = new PartyKey(fakechar.getWorld(), partyId);
            join = PARTY_JOINS.computeIfAbsent(partyKey, key -> new PartyJoin());
            join.users++;
            JOINING_BOTS.add(botId);
        }

        boolean joined;
        join.lock.lock();
        try {
            joined = Party.joinParty(fakechar, partyId, false);
        } finally {
            synchronized (queue) {
                join.lock.unlock();
                if (--join.users == 0) {
                    PARTY_JOINS.remove(partyKey);
                }
                JOINING_BOTS.remove(botId);
            }
        }
        if (!joined && inviter != null) {
            // The host doesn't tell the inviter why the join failed.
            inviter.sendPacket(PacketCreator.serverNotice(5,
                    BotMessages.get("party.join_failed", fakechar.getName())));
        }
        debugprint("botAcceptPartyInvite: joined=" + joined + " partyId=" + partyId);
        return joined;
    }

    public static boolean botRejectPartyInvite(Character fakechar) {
        BotPartyQueue queue = BotPartyQueue.getInstance();
        BotPartyQueue.PartyInviteEntry entry;
        InviteResult res;
        synchronized (queue) {
            entry = queue.getPartyInvite(fakechar);
            if (entry == null) {
                debugprint("botRejectPartyInvite: no pending invite, no-op.");
                return false;
            }

            res = InviteCoordinator.answerInvite(InviteType.PARTY, fakechar.getId(), entry.getPartyId(), false);
            queue.removePartyInvite(fakechar);
        }

        // Only tell the inviter the bot declined when the coordinator entry actually existed and was
        // denied (DENIED). A NOT_FOUND means the invite was already gone/superseded, so the notice
        // would be misleading - clear the queue entry either way.
        Character inviter = entry.getInviter();
        if (inviter != null && res.result == InviteResultType.DENIED) {
            inviter.sendPacket(PacketCreator.serverNotice(5,
                    BotMessages.get("party.declined", fakechar.getName())));
        }
        debugprint("botRejectPartyInvite: result=" + res.result + " inviter=" + (inviter == null ? "?" : inviter.getName()));
        return true;
    }

    // Bot sends a party invite to a real player.
    // If bot has no party, creates one (bot becomes leader).
    // If bot is already in a party but NOT the leader, the invite is refused.
    public static boolean botInvitePlayer(Character fakechar, Character target) {
        if (target == null) {
            debugprint("botInvitePlayer: target null.");
            return false;
        }
        if (target.getParty() != null) {
            debugprint("botInvitePlayer: target already in a party.");
            return false;
        }

        Party party = fakechar.getParty();
        if (party == null) {
            if (!Party.createParty(fakechar, false)) {
                debugprint("botInvitePlayer: failed to create party for bot.");
                return false;
            }
            party = fakechar.getParty();
        } else if (party.getLeaderId() != fakechar.getId()) {
            debugprint("botInvitePlayer: bot is in a party but not the leader, cannot invite.");
            return false;
        }

        if (party.getMembers().size() >= 6) {
            debugprint("botInvitePlayer: party is full.");
            return false;
        }

        if (InviteCoordinator.createInvite(InviteType.PARTY, fakechar, party.getId(), target.getId())) {
            target.sendPacket(PacketCreator.partyInvite(fakechar));
            debugprint("botInvitePlayer: invite sent to " + target.getName() + " for partyId=" + party.getId());
            return true;
        }

        debugprint("botInvitePlayer: InviteCoordinator rejected (target already has pending invite).");
        return false;
    }
}
