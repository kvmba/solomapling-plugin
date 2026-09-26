package soloMapling.ArtificialPlayer.BotTypes.Horntail;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Horntail PQ ("Cave of Trial") with a real player leading.
 *
 * <p>Five rooms, one key each, and the same loop in all of them: kill until this room's key
 * drops, take it, move on. Which key that is comes from the room number, and which monster
 * carries it comes from the drop table, so the bot knows what it is waiting for instead of
 * killing everything and hoping.
 *
 * <p>It does not gather the keys into the party's hands beyond its own: Aura checks the
 * inventory of whoever talks to him and wants all five at once, so the leader turns them in.
 * The bot's share of that is whatever it happens to have picked up, which is why it still
 * collects them rather than leaving them on the floor.
 */
public class HorntailPQBot extends PartyQuestBot {

    private static final int FIGHT_PASSES = 20;

    public HorntailPQBot(Character character) {
        super(character);
        dialoguePath = "HorntailPQBotDialogue.yaml";
        botType = "HorntailPQBot";
        questName = "HorntailPQ";
        // The rooms' reactor scripts resolve their player through client.getPlayer() when
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
        return HorntailPqData.isQuestRoom(mapId);
    }

    @Override
    protected int lobbyMapId() {
        return HorntailPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        int index = HorntailPqData.roomIndexOf(getChr().getMapId());
        if (index < 0) {
            // Past the key rooms: the light/dark choice and the boss are the leader's to make
            // and the attack path's to fight. Keep hitting whatever is here.
            PqActions.seekAndAttack(getChr());
            return false;
        }

        int key = HorntailPqData.keyForRoom(index);
        if (PqActions.countItem(getChr(), key) > 0) {
            // The key is in hand - hand it on: Aura checks the inventory of whoever talks
            // to him (the leader) and wants all five at once, so a bot holding its room's
            // key starves the turn-in and the run stalls at the last door.
            PqActions.handItemsToLeader(getChr(), key);
            return false; // the leader walks the party on once all five reach him
        }

        // Kill this room's monsters, then sweep for the key. The room holds a pair and only
        // one of them carries it, so the loop is bounded by the fight rather than by a count.
        for (int pass = 0; pass < FIGHT_PASSES && PqActions.countItem(getChr(), key) == 0; pass++) {
            PqActions.seekAndAttack(getChr());
            PqActions.loot(getChr(), getChr().getPosition(), 1_200, new int[]{key});
        }
        PqActions.handItemsToLeader(getChr(), key);
        return false;
    }
}
