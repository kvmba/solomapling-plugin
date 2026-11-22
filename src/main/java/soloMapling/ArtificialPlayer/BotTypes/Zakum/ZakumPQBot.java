package soloMapling.ArtificialPlayer.BotTypes.Zakum;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Zakum PQ with a real player leading.
 *
 * <p>Help break ordinary crates and collect the ore once the party opens the ore chest
 * with the mine keys. The ore chest is not a strike reactor.
 *
 * <p>The turn-in stays with the player: Aura takes the ore from whoever talks to him, and the
 * exit portal checks that the instance has been cleared rather than that anyone is carrying
 * anything - so the bot gathers and stops, and the leader does the rest.
 */
public class ZakumPQBot extends PartyQuestBot {

    private static final int[] ORE_LOOT = {ZakumPqData.FIRE_ORE};

    public ZakumPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
        botType = "ZakumPQBot";
        questName = "ZakumPQ";
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
    protected String questChatPool(Beat beat) {
        if (beat == Beat.START && getChr().getMapId() != ZakumPqData.ENTRY_MAP) return null;
        if (beat == Beat.WORKING && !PqActions.needsHuntForLeader(getChr(), ZakumPqData.FIRE_ORE, 1)) return null;
        return questName;
    }

    @Override
    protected boolean wantsQuestLoot(int itemId) {
        return itemId == ZakumPqData.FIRE_ORE;
    }

    @Override
    protected boolean workStage() {
        Character bot = getChr();
        PqActions.armReactorBeat(bot, -1);
        if (!PqActions.needsHuntForLeader(bot, ZakumPqData.FIRE_ORE, 1)) {
            PqActions.deliverAndWait(bot, ZakumPqData.FIRE_ORE, 2032002);
            return false;
        }
        int chest = PqActions.findReactorOid(bot, ZakumPqData.ORE_CHEST);
        if (chest >= 0 && PqActions.feedReactor(bot, chest, ZakumPqData.MINE_KEY)) return false;

        switch (PqActions.collectPartyQuestLoot(bot, ORE_LOOT)) {
            case PICKED_UP -> PqActions.deliverAndWait(bot, ZakumPqData.FIRE_ORE, 2032002);
            case COLLECTING -> { }
            case NONE -> PqActions.workActiveReactors(bot, ZakumPqData.CRATES);
        }
        return false;
    }
}
