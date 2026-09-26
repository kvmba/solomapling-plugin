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

    /** The NPC standing in the maze whose talk warps the team out of it. */
    private static final int MAZE_NPC = 2133001;

    /** The last map the bot asked the maze NPC from, so the ask fires once per visit. */
    private int mazeExitAsked = -1;

    public EllinPQBot(Character character) {
        super(character);
        dialoguePath = "EllinPQBotDialogue.yaml";
        botType = "EllinPQBot";
        questName = "EllinPQ";
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

        // The maze's own door out: the NPC at the maze's centre warps the whole team to the
        // frog room when anyone talks to him - that is the only way past 930000300, whose
        // script portals all lead back into the maze itself. The bot asks once per visit;
        // the actual warp carries whoever is in the maze, the leader included.
        if (mapId == EllinPqData.MAZE_MAP) {
            askMazeExit();
        }

        // The frog room wants the Poison Golem's guards CAUGHT, not killed (NPC 2133001
        // hands out purifiers and grades 20 Monster Marbles), and a dead frog never drops
        // one - so the bot adds no swings there at all.
        if (mapId == EllinPqData.FROG_ROOM) {
            return false;
        }

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
                PqActions.seekAndAttack(getChr());
            }
        }
        return false;
    }

    /**
     * Ask the maze's NPC (2133001) to open the way out, once per maze visit.
     *
     * <p>The script warps the whole team to the frog room on the confirmation click, so the
     * bot's talk is not self-serving - it is the door. Clicks are paced by the engine's own
     * 500ms throttle inside {@code PqActions.talkTo}.
     */
    private void askMazeExit() {
        if (mazeExitAsked == getChr().getMapId() && PqActions.inInstance(getChr())) {
            return; // already asked on this visit; the warp either happened or is pending
        }
        mazeExitAsked = getChr().getMapId();
        PqActions.talkTo(getChr(), MAZE_NPC, 0);
    }
}
