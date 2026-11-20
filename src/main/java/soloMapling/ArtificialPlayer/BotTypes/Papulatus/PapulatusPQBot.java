package soloMapling.ArtificialPlayer.BotTypes.Papulatus;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

/**
 * Fights Papulatus with a real player leading.
 *
 * <p>The shortest loop of any quest bot here, because the room has nothing to work out: it
 * starts empty, the party summons the boss by dropping an item on a reactor, and the three
 * stages revive into each other until the last one dies. So the bot fights while anything is
 * standing and waits with the party otherwise - no stage flags, no boxes, no turn-ins.
 *
 * <p>Two decisions worth stating:
 * <ul>
 *   <li>The item stays the player's. The bot cannot summon (and must not conjure the piece),
 *       so a room with no monster is a room where the party has not dropped it yet - the bot
 *       stands ready rather than inventing work.</li>
 *   <li>The bot leaves when the leader leaves, and only then. The room has no exit portal -
 *       the party leaves by talking to the tombstone NPC - so the leader's presence is the one
 *       thing that says whether the run is still on.</li>
 * </ul>
 */
public class PapulatusPQBot extends PartyQuestBot {

    /**
     * Whether this bot has already spent a tick inside the room during the current run.
     *
     * <p>Its only job is to keep the party's own arrival from reading as a departure. The event
     * script registers and warps the party member by member, so on the tick this bot lands the
     * leader may still be a step behind it - and leaving on that tick would walk out of a run
     * that had only just started. From the next tick on the answer is literal: he is in the room
     * or he is not.
     */
    private boolean seenInsideRoom;

    public PapulatusPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
        botType = "PapulatusPQBot";
        questName = "PapulatusBattle";
        // The reactor's act() resolves its player through client.getPlayer() when the drop
        // fires five seconds later, so this bot needs a client of its own.
        BotGeneration.adoptPrivateClient(character);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        // Every tick outside the room is between runs (or a run this bot has just been recruited
        // into), so the arrival grace is earned again on the next one.
        if (!isInsideQuest(getChr().getMapId())) {
            seenInsideRoom = false;
        }
        tickPartyQuest();
    }

    @Override
    protected boolean isInsideQuest(int mapId) {
        return PapulatusPqData.isBossRoom(mapId);
    }

    @Override
    protected int lobbyMapId() {
        return PapulatusPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        if (isRunCleared()) {
            settleRewardRoom(2041025);
            return false;
        }
        if (leaderIsGone()) {
            returnToLobby("the leader left the run");
            return false;
        }
        if (!getChr().getMap().getMonsters().isEmpty()) {
            // Fight what is here. The attack driver picks its own target, so the sphere, the
            // clock and the body are all just "something standing in the room" to it - and so
            // are the small darkstars the clock summons.
            PqActions.seekAndAttack(getChr());
        }
        // A room with nothing in it is either one where the party has not dropped the piece yet
        // or one whose clock just fell over. Both are "stay with them": the party is standing
        // right here, and the room has no exit portal for anyone to have walked through.
        return false;
    }

    @Override
    protected boolean fightsOnSweep() {
        return !isRunCleared();
    }

    /**
     * Whether the leader has left the run.
     *
     * <p>Read literally: the bot follows the leader, so a leader who is not in the room means the
     * run is over for this bot too - no grace period and no waiting for the quest's timer. Two
     * leaders count as gone even when a party record still exists, because neither is a real
     * player to follow: nobody at all (he disconnected), or another bot (the last real player
     * handed the party over).
     *
     * <p>The one exception is the party's own arrival - see {@link #seenInsideRoom}.
     */
    private boolean leaderIsGone() {
        if (!seenInsideRoom) {
            seenInsideRoom = true;
            return false; // first tick in the room: the rest of the party may still be landing
        }
        Character leader = partyLeader();
        if (leader == null || isBot(leader)) {
            return true;
        }
        return leader.getMapId() != PapulatusPqData.BOSS_ROOM;
    }

    /** Whether the run's boss is down - the quest's own clear flag. */
    private boolean isRunCleared() {
        var eim = getChr().getEventInstance();
        return eim != null && eim.isEventCleared();
    }

    @Override
    protected void releaseRoomState() {
        super.releaseRoomState();
        seenInsideRoom = false;
    }

    @Override
    public synchronized void stopScheduledTask() {
        super.stopScheduledTask();
        PqActions.leaveEventInstance(getChr());
    }
}
