package soloMapling.ArtificialPlayer.BotTypes.Pyramid;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Nett's Pyramid with a real player leading.
 *
 * <p>Unlike every other bot here, this one is built around <em>not</em> attacking things. The
 * run lives on a gauge: killing feeds it, missing takes a large bite out of it, and at zero
 * the party fails. The quest marks the one monster that costs the miss, so the bot leaves it
 * alone and works the rest - which is a rule about restraint, and the only place in this
 * package where the right move is to hold a swing.
 *
 * <p>There is no stage flag to read here. The class keeps its gauge internally and the party
 * sees it as a broadcast, so the bot works from what is in the room: monsters it may kill,
 * and monsters it may not.
 */
public class PyramidPQBot extends PartyQuestBot {

    /** How many swings to spend before letting the tick end. */
    private static final int FIGHT_PASSES = 15;

    public PyramidPQBot(Character character) {
        super(character);
        dialoguePath = "PyramidPQBotDialogue.yaml";
        botType = "PyramidPQBot";
        // The pyramid's gauge and its spawned monsters resolve their player through
        // client.getPlayer(), so this bot needs a client of its own rather than the shared one.
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
        return PyramidPqData.isPyramidMap(mapId);
    }

    @Override
    protected int lobbyMapId() {
        // Duarte's menu map (the Pyramid Dunes), which is where a party is applied for and where
        // the bots stand. Not NETTS_PYRAMID (926010001): that is inside the run, so returning a
        // finished bot there would leave it stranded where nobody recruits.
        return PyramidPqData.LOBBY_MAP;
    }

    @Override
    protected boolean workStage() {
        // Attack only if there is something here that is safe to attack. The attack driver
        // picks its own target from what is in reach, so the room is checked first rather
        // than trusting it to avoid the marked monsters.
        for (int pass = 0; pass < FIGHT_PASSES; pass++) {
            if (!hasOnlyKillableMonsters()) {
                break;
            }
            PqActions.attack(getChr());
        }
        return false;
    }

    /**
     * Whether everything in reach is safe to attack.
     *
     * <p>Conservative on purpose: if the forbidden Pharaoh Jr. Yeti is anywhere in the room, the
     * bot stops swinging rather than gambling that the target it picks will be a safe one. A miss
     * costs the party more than a paused bot does, and the quest's own warning is explicit.
     */
    private boolean hasOnlyKillableMonsters() {
        var mobs = getChr().getMap().getAllMonsters();
        if (mobs.isEmpty()) {
            return false;
        }
        for (var mob : mobs) {
            if (PyramidPqData.isForbidden(mob.getId())) {
                return false;
            }
        }
        return true;
    }
}
