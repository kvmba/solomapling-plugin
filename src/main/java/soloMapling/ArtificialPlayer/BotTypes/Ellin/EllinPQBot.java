package soloMapling.ArtificialPlayer.BotTypes.Ellin;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Ellin PQ ("Ellin Forest") with a real player leading.
 *
 * <p>The quest alternates between two instructions, and its own portals state both: a room is
 * either "kill what is here" or "break what is blocking the way". The bot answers whichever
 * applies to the room it is standing in, checking the same conditions the doors check - so if
 * the portal would refuse, the bot has already seen why.
 *
 * <p>The maze room is handled the way Ludi's tower is: its portals mostly lead back to where
 * the traveller came from, so the bot steps through and judges by whether it arrived anywhere
 * new.
 */
public class EllinPQBot extends PartyQuestBot {

    private static final int FIGHT_PASSES = 20;

    /** Where the bot has already been in the maze, so a dead-end portal is not re-tried. */
    private int lastMazeMap = -1;

    public EllinPQBot(Character character) {
        super(character);
        dialoguePath = "EllinPQBotDialogue.yaml";
        botType = "EllinPQBot";
        // The quest's reactor scripts resolve their player through client.getPlayer() when
        // they run, so this bot needs a client of its own rather than the shared one.
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
        return EllinPqData.isQuestRoom(mapId);
    }

    @Override
    protected int lobbyMapId() {
        return EllinPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        int mapId = getChr().getMapId();
        var map = getChr().getMap();

        // The spine blocks the way until it is broken, in the room that has it.
        int spine = PqActions.findReactorOid(getChr(), EllinPqData.SPINE_REACTOR);
        if (spine >= 0) {
            for (int guard = 0; guard < 8 && spine >= 0; guard++) {
                PqActions.hitReactor(getChr(), spine);
                spine = PqActions.findReactorOid(getChr(), EllinPqData.SPINE_REACTOR);
            }
        }

        // Then whatever is standing in the room.
        if (!map.getMonsters().isEmpty()) {
            for (int pass = 0; pass < FIGHT_PASSES && !map.getMonsters().isEmpty(); pass++) {
                PqActions.attack(getChr());
            }
        }

        // The maze sends travellers back more often than forward, so a step through it is
        // only progress if the map actually changed.
        if (mapId == EllinPqData.MAZE_MAP) {
            int before = mapId;
            PqActions.takePortal(getChr(), 1);
            PqActions.holdArea(getChr(), getChr().getPosition(), 400);
            lastMazeMap = getChr().getMapId();
            if (getChr().getMapId() == before) {
                // That portal was a dead end; the next tick tries the following one.
                return false;
            }
        }
        return false;
    }

    /** Which room the bot last reached through the maze, for a caller wanting to log it. */
    public int lastMazeMap() {
        return lastMazeMap;
    }
}
