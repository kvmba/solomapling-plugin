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

    private static final int[] SIDE_KEYS = {4001092, 4001093};

    public HorntailPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
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
    protected boolean fightsOnSweep() {
        int mapId = getChr().getMapId();
        if (mapId == HorntailPqData.LIGHT_ROOM || mapId == HorntailPqData.DARK_ROOM) {
            return needsSideKeys();
        }
        return true; // the early keys also feed reactors that open the next cave
    }

    private boolean needsSideKeys() {
        return PqActions.needsHuntForLeader(getChr(), SIDE_KEYS[0], 1)
                || PqActions.needsHuntForLeader(getChr(), SIDE_KEYS[1], 6);
    }

    @Override
    protected boolean workStage() {
        int index = HorntailPqData.roomIndexOf(getChr().getMapId());
        if (index < 0) {
            int mapId = getChr().getMapId();
            if (mapId == HorntailPqData.LIGHT_ROOM || mapId == HorntailPqData.DARK_ROOM) {
                if (needsSideKeys()) {
                    PqActions.seekAndAttack(getChr(), false, SIDE_KEYS);
                    PqActions.loot(getChr(), getChr().getPosition(), 1_200, SIDE_KEYS);
                } else {
                    for (int key : SIDE_KEYS) {
                        PqActions.deliverAndWait(getChr(), key, HorntailPqData.AURA);
                    }
                }
                return false;
            }
            // Past the key rooms: the light/dark choice and the boss are the leader's to make
            // and the attack path's to fight. Keep hitting whatever is here.
            PqActions.seekAndAttack(getChr());
            return false;
        }

        int key = HorntailPqData.keyForRoom(index);
        if (PqActions.countItem(getChr(), key) > 0) {
            // These keys feed the cave reactors rather than Aura's final turn-in.
            PqActions.handItemsToLeader(getChr(), key);
            return false; // the cave's reactor still has to open the next door
        }

        // Kill this room's monsters, then sweep for the key. The room holds a pair and only
        // one of them carries it, so one seek beat plus a loot sweep per macro tick is the
        // whole job - the swings land on the shared combat sweep; the loot sweep must stay
        // on this bounded macro tick or a key that never reaches the inventory (inventory
        // full, drop uncollected) would spin here forever.
        if (PqActions.countItem(getChr(), key) == 0) {
            PqActions.seekAndAttack(getChr());
            PqActions.loot(getChr(), getChr().getPosition(), 1_200, new int[]{key});
        }
        // A key the leader missed on the floor despawns with the room's other drops; take it
        // back and offer it again next tick.
        PqActions.recoverUngatheredHandoffs(getChr(), key);
        PqActions.handItemsToLeader(getChr(), key);
        return false;
    }
}
