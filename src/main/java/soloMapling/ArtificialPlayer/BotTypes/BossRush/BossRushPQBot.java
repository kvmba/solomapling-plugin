package soloMapling.ArtificialPlayer.BotTypes.BossRush;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Boss Rush PQ with a real player leading.
 *
 * <p>The whole quest is "clear the room, then walk through the portal", which the engine
 * itself enforces ({@code raid_stage.js} refuses to let anyone through while monsters are
 * alive). That makes this bot's loop unusually honest: fight while anything is standing, walk
 * to the portal once nothing is, and repeat. There is nothing to read and nothing to guess.
 *
 * <p>It is the cheapest test of the combat half of the capability layer, and the only quest
 * that can be attempted solo - the quest's own floor is one player - so it doubles as a way
 * to check a bot's attacks land without assembling a party first.
 */
public class BossRushPQBot extends PartyQuestBot {

    /** How many swing passes to spend on one room before letting the tick end. */
    private static final int FIGHT_PASSES = 20;

    public BossRushPQBot(Character character) {
        super(character);
        dialoguePath = "BossRushPQBotDialogue.yaml";
        botType = "BossRushPQBot";
        questName = "BossRushPQ";
        // The quest's own scripts resolve their player through client.getPlayer() when they
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
        return BossRushPqData.isFightRoom(mapId) || BossRushPqData.isRestSpot(mapId)
                || mapId == BossRushPqData.HOST_MAP;
    }

    @Override
    protected int lobbyMapId() {
        return BossRushPqData.HOST_MAP;
    }

    @Override
    protected boolean workStage() {
        var map = getChr().getMap();
        if (map.getMonsters().isEmpty()) {
            // The room is clear. Whether to move on is the leader's call - he is the one the
            // host asks, and the portal he walks is the one everyone follows - so the bot only
            // has to be standing somewhere it can follow from.
            return false;
        }
        // Fight what is here. The attack driver picks its own target, so a boss room is the
        // same to this bot as any other room full of monsters.
        for (int pass = 0; pass < FIGHT_PASSES && !map.getMonsters().isEmpty(); pass++) {
            PqActions.seekAndAttack(getChr());
        }
        return false;
    }
}
