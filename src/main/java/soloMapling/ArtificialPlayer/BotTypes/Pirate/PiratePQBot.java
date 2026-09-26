package soloMapling.ArtificialPlayer.BotTypes.Pirate;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Pirate PQ ("Lord Pirate") with a real player leading.
 *
 * <p>Every room in this quest is the same instruction: break the boxes, deal with whatever
 * comes out, move on when the room is quiet. Rooms 2 to 4 have boxes standing in them, room 1
 * has the monsters on the floor already and room 4 hides its monsters inside the boxes, so
 * the loop is written as "break what is breakable, then fight what is left" and covers all of
 * them without special cases.
 *
 * <p>Which room the party is in is taken from the map, which is also what the quest itself
 * keys on - it bumps its {@code curStage} as the leader walks into each room, so the map is
 * the leading signal rather than a flag that follows it.
 */
public class PiratePQBot extends PartyQuestBot {

    /** How many swings to spend on one room before letting the tick end. */
    private static final int FIGHT_PASSES = 20;

    public PiratePQBot(Character character) {
        super(character);
        dialoguePath = "PiratePQBotDialogue.yaml";
        botType = "PiratePQBot";
        questName = "PiratePQ";
        // The quest's box scripts resolve their player through client.getPlayer() when they
        // run, so this bot needs a client of its own rather than the shared per-channel one.
        BotGeneration.adoptPrivateClient(character);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        tickPartyQuest();
    }

    @Override
    protected boolean isInsideQuest(int mapId) {
        return mapId >= PiratePqData.ENTRY_MAP && mapId <= PiratePqData.STAGE_5;
    }

    @Override
    protected int lobbyMapId() {
        return PiratePqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        var map = getChr().getMap();

        // Boxes first: in room 4 the monsters are inside them, so hitting boxes is also how
        // the fight starts. findReactorOid returns the first one still alive, so this walks
        // the room's boxes one at a time until none are left.
        for (int boxId : PiratePqData.boxesIn(map.getId())) {
            for (int guard = 0; guard < BOXES_PER_ROOM_LIMIT; guard++) {
                int oid = PqActions.findReactorOid(getChr(), boxId);
                if (oid < 0) {
                    break; // no more of this kind standing
                }
                PqActions.hitReactor(getChr(), oid);
                if (!stillAlive(oid)) {
                    break; // this one is spent; the next pass finds the following one
                }
            }
        }

        // Then whatever the room has left standing.
        if (!map.getMonsters().isEmpty()) {
            for (int pass = 0; pass < FIGHT_PASSES && !map.getMonsters().isEmpty(); pass++) {
                PqActions.seekAndAttack(getChr());
            }
        }
        // The medal stages' turn-in (NPC 2094002) grades the leader's pockets, so anything
        // the bot picked up - medals and the like from the run's exclusive set - goes to him.
        for (int itemId : PiratePqData.QUEST_ITEMS) {
            PqActions.handItemsToLeader(getChr(), itemId);
        }
        return false;
    }

    /** How many of one box kind to keep hitting before giving the tick back. */
    private static final int BOXES_PER_ROOM_LIMIT = 24;

    private boolean stillAlive(int reactorOid) {
        var reactor = getChr().getMap().getReactorByOid(reactorOid);
        return reactor != null && reactor.isAlive();
    }
}
