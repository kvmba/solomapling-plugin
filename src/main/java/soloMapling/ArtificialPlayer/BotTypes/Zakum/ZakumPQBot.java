package soloMapling.ArtificialPlayer.BotTypes.Zakum;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Zakum PQ with a real player leading.
 *
 * <p>The mine holds nothing but crates, so the loop is short: break crates, pick up the ore
 * that falls out, repeat until the party has the Fire Ore it came for. There are no monsters
 * anywhere in the instance, which is why this bot needs no fight handling at all.
 *
 * <p>The turn-in stays with the player: Aura takes the ore from whoever talks to him, and the
 * exit portal checks that the instance has been cleared rather than that anyone is carrying
 * anything - so the bot gathers and stops, and the leader does the rest.
 */
public class ZakumPQBot extends PartyQuestBot {

    /** How many crates to work through in one tick before handing the thread back. */
    private static final int CRATE_PASSES = 24;

    public ZakumPQBot(Character character) {
        super(character);
        dialoguePath = "ZakumPQBotDialogue.yaml";
        botType = "ZakumPQBot";
        // The crates' state changes are read through the reactor API, which resolves its
        // player via client.getPlayer(), so this bot needs a client of its own.
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
        return ZakumPqData.isQuestRoom(mapId);
    }

    @Override
    protected int lobbyMapId() {
        return ZakumPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        // Break what crates are standing, taking the first of each kind in turn. The mine's
        // crates carry no scripts, so a hit is just a state change and there is nothing to
        // wait for beyond the crate disappearing.
        for (int pass = 0; pass < CRATE_PASSES; pass++) {
            int oid = firstStandingCrate();
            if (oid < 0) {
                break;
            }
            PqActions.hitReactor(getChr(), oid);
        }

        // Whatever fell out is the point of the room.
        PqActions.loot(getChr(), getChr().getPosition(), 2_000,
                new int[]{ZakumPqData.FIRE_ORE, ZakumPqData.FIRE_ORE_REFINED});
        return false;
    }

    /** The first crate still standing in the room, or -1 when the room is cleared. */
    private int firstStandingCrate() {
        for (int crateId : ZakumPqData.CRATES) {
            int oid = PqActions.findReactorOid(getChr(), crateId);
            if (oid >= 0) {
                return oid;
            }
        }
        return -1;
    }
}
