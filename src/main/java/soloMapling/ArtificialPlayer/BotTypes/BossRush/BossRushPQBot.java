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

    public BossRushPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
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
        // The host map is NOT part of the run, even though the quest's own script uses it as
        // both recruit and exit map: it is the room a player walks into to assemble a party,
        // so a bot standing there has to be recruitable. Treating it as in-run (the old shape)
        // made awaitingRecruit() false in its own lobby, which silenced both the recruit shout
        // and the answer to a player's "anyone want to party?" - the crowd was there and
        // unreachable except by right-click invite.
        return BossRushPqData.isFightRoom(mapId) || BossRushPqData.isRestSpot(mapId);
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
        if (!map.getMonsters().isEmpty()) {
            PqActions.seekAndAttack(getChr());
        }
        return false;
    }
}
