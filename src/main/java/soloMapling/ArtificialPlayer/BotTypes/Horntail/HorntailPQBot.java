package soloMapling.ArtificialPlayer.BotTypes.Horntail;

import org.gms.client.Character;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Horntail PQ ("Cave of Trial") with a real player leading.
 *
 * <p>The entry keeps one guard. Each inner cave feeds its key to passKeyN, which sends that
 * key back to the entry and opens the next cave. The entry guard gathers all five keys for
 * the leader's NPC turn-in; the last cave's red key opens the leader's return door.
 */
public class HorntailPQBot extends PartyQuestBot {

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

    /** The last entry occupant is a guard, not a failed follower. */
    @Override
    protected boolean isFollowingLeader() {
        int mapId = getChr().getMapId();
        return mapId < HorntailPqData.ENTRY_MAP || mapId > HorntailPqData.LAST_ROOM;
    }

    @Override
    protected Portal scriptedForwardPortal() {
        var map = getChr().getMap();
        if (map == null) {
            return null;
        }
        Portal exit = PartyQuestBot.scriptedPortalOf(map.getPortals(), "in00", "hontale_BtoB1");
        return exit != null ? exit
                : PartyQuestBot.scriptedPortalOf(map.getPortals(), "in00", "hontale_Bopen");
    }

    @Override
    protected boolean workStage() {
        Character bot = getChr();
        int room = bot.getMapId() - HorntailPqData.ENTRY_MAP;
        if (room == 0) {
            boolean returning = PqActions.readEimString(bot, "5stageclear") != null;
            if (!isEntryGuard()) {
                if (!returning && bot.getMap().countPlayers() > 1) enterNextCave();
                return false;
            }
            PqActions.seekAndAttack(bot);
            PqActions.loot(bot, bot.getPosition(), 1_200, HorntailPqData.CRYSTAL_KEYS);
            for (int key : HorntailPqData.CRYSTAL_KEYS) {
                PqActions.recoverUngatheredHandoffs(bot, key);
                // The first key blocks the leader's ENTRY door. Hand the collection over
                // only after his red-key return has opened the last cave's door.
                if (returning) PqActions.handItemsToLeader(bot, key);
            }
            return false;
        }
        if (room >= 1 && room <= 4) {
            if (PqActions.readEimString(bot, room + "stageclear") != null) {
                enterNextCave();
                return false;
            }
            int key = HorntailPqData.keyForRoom(room);
            PqActions.seekAndAttack(bot);
            PqActions.loot(bot, bot.getPosition(), 1_200, new int[]{key});
            var reactor = bot.getMap().getReactorByName("passKey" + room);
            if (reactor != null) PqActions.feedReactor(bot, reactor.getObjectId(), key);
            return false;
        }
        PqActions.seekAndAttack(bot);
        if (room == 5 || bot.getMapId() == HorntailPqData.LIGHT_ROOM
                || bot.getMapId() == HorntailPqData.DARK_ROOM) {
            PqActions.loot(bot, bot.getPosition(), 1_200, HorntailPqData.FINAL_KEYS);
            for (int key : HorntailPqData.FINAL_KEYS) {
                PqActions.recoverUngatheredHandoffs(bot, key);
                PqActions.handItemsToLeader(bot, key);
            }
            if (room == 5 && PqActions.readEimString(bot, "5stageclear") != null) enterNextCave();
        }
        return false;
    }

    private void enterNextCave() {
        Portal exit = scriptedForwardPortal();
        if (exit != null && PqActions.walkTo(getChr(), exit.getPosition())) {
            PqActions.enterPortal(getChr(), exit);
        }
    }

    private boolean isEntryGuard() {
        int guardId = getChr().getId();
        var party = getChr().getParty();
        if (party == null) return true;
        for (var member : party.getMembers()) {
            var teammate = CharacterStorage.getBotById(member.getId());
            if (teammate instanceof HorntailPQBot
                    && teammate.getChr().getEventInstance() == getChr().getEventInstance()) {
                guardId = Math.max(guardId, member.getId());
            }
        }
        return guardId == getChr().getId();
    }

    @Override
    public boolean allowsPetPickup(int itemId) {
        int room = getChr().getMapId() - HorntailPqData.ENTRY_MAP;
        // Item-triggered passKey reactors consume these exact ground piles after a delay.
        if (room >= 1 && room <= 4 && itemId == HorntailPqData.keyForRoom(room)) return false;
        return room != 0 || itemId < HorntailPqData.KEY_FIRST
                || itemId >= HorntailPqData.KEY_FIRST + HorntailPqData.KEY_COUNT || isEntryGuard();
    }
}
