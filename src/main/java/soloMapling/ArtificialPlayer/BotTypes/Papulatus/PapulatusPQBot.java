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
 *
 * <p>Leaving on the leader's cue has one consequence the other quest bots never had to face;
 * see {@link #releaseRoomState()}.
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
        dialoguePath = "PapulatusPQBotDialogue.yaml";
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
        if (leaderIsGone()) {
            if (isRunCleared()) {
                return true; // base class owns the cleared exit (and the clear beat)
            }
            // Abandoned: leave here, because the base class would announce a clear that never
            // happened.
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

    /**
     * Hand the instance back when the party has emptied out of the room.
     *
     * <p>Why this bot needs it when the others do not: the script ends a run from
     * {@code changedMap}, and that test is {@code isExpeditionTeamLackingNow}, i.e.
     * {@code getPlayerCount() <= 1} evaluated <em>before</em> the leaver is unregistered. A
     * player walking out of a room where bots are still standing therefore reads as "one player
     * left, the team is fine" and nothing ends - which was invisible before because those bots
     * never left. This bot leaves on the leader's cue, so the party does empty out, and someone
     * has to close the room.
     *
     * <p>The order matters: detach first, then look. Several bots of one party walk out on the
     * same cue, so a plain "is the room empty?" test would see the others still registered and
     * leave nobody to dispose; each one removes itself first, and the last one sees zero. The
     * detach is the same call the base class makes moments later and is harmless twice.
     *
     * <p>What it must NOT do: dispose while another member is still in the room. The count
     * guard is the whole protection - a run the player abandons with bots still fighting keeps
     * its instance, which is the pre-existing behaviour for every other quest.
     *
     * <p>Called from the base class's stop path as well as its exit path, so it has to stay safe
     * for a bot that is merely being retyped: with no instance there is nothing to do.
     */
    @Override
    protected void releaseRoomState() {
        seenInsideRoom = false;
        Character bot = getChr();
        var eim = bot == null ? null : bot.getEventInstance();
        if (eim == null) {
            return; // not in a run (a lobby bot, or one already detached)
        }
        eim.unregisterPlayer(bot);
        if (eim.getPlayerCount() == 0) {
            eim.dispose();
        }
    }
}
