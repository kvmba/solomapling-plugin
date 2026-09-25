package soloMapling.ArtificialPlayer.BotTypes.Dojo;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Coaches a party up the Mu Lung Dojo.
 *
 * <p>A tower of bosses and nothing else: each room is a fight, the door to the next opens when
 * the room is clear, and the party climbs until it runs out of time or people. So the bot adds
 * damage and stays with the group, and the interesting part of this quest is not in the bot at
 * all - it is in getting in.
 *
 * <p>Entry is the risky step, and this bot deliberately does not attempt it. The channel hands
 * out five party slots as a shared resource and a bot party would occupy one a player party
 * then cannot have; and the level rule is a spread of thirty rather than a range, so a bot
 * added carelessly can block an otherwise legal party from entering. Both are decisions for
 * whoever is assembling the party, not for a bot to take on its own.
 */
public class DojoPQBot extends PartyQuestBot {

    private static final int FIGHT_PASSES = 20;

    public DojoPQBot(Character character) {
        super(character);
        dialoguePath = "DojoPQBotDialogue.yaml";
        botType = "DojoPQBot";
        questName = "Dojo";
        // The tower's spawns and the party warps resolve their player through
        // client.getPlayer(), so this bot needs a client of its own.
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
        return DojoPqData.isPartyDojo(mapId);
    }

    @Override
    protected int lobbyMapId() {
        return DojoPqData.DOJO_HALL;
    }

    @Override
    protected boolean workStage() {
        var map = getChr().getMap();
        // The dojo's rooms are one boss each and the door opens when it is down, so there is
        // nothing to read - just fight while anything is standing.
        for (int pass = 0; pass < FIGHT_PASSES && !map.getMonsters().isEmpty(); pass++) {
            PqActions.attack(getChr());
        }
        return false;
    }
}
