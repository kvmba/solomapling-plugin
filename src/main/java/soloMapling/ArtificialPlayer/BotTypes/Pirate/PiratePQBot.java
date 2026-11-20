package soloMapling.ArtificialPlayer.BotTypes.Pirate;

import org.gms.client.Character;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Pirate PQ ("Lord Pirate") with a real player leading.
 *
 * <p>The medal room has three separate leader turn-ins. In the other rooms the bot breaks
 * boxes and fights what is still standing.
 *
 * <p>Which room the party is in is taken from the map, which is also what the quest itself
 * keys on - it bumps its {@code curStage} as the leader walks into each room, so the map is
 * the leading signal rather than a flag that follows it.
 */
public class PiratePQBot extends PartyQuestBot {

    private static final int MEDALS_PER_ROUND = 20;
    private static final int STAGE_NPC = 2094002;
    private static final int[][] MEDAL_LOOT_BY_ROUND = {{4001120}, {4001121}, {4001122}};

    public PiratePQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
        botType = "PiratePQBot";
        questName = "PiratePQ";
        // The quest's box scripts resolve their player through client.getPlayer() when they
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
        return mapId >= PiratePqData.ENTRY_MAP && mapId <= PiratePqData.STAGE_5;
    }

    @Override
    protected Portal forwardPortal(int leaderMapId) {
        var map = getChr().getMap();
        int room = map.getId();
        int stage = room - room % 100;
        if (leaderMapId < stage + 100 || stage >= PiratePqData.STAGE_5) {
            return null;
        }
        // The alternate in01 script resets the side room's monsters; don't trail a leader into it.
        return map.getPortal(room == stage ? "next00" : "out00");
    }

    @Override
    protected int lobbyMapId() {
        return PiratePqData.RECRUIT_MAP;
    }

    @Override
    protected boolean fightsOnSweep(int[] lootIds) {
        Character bot = getChr();
        if (bot.getMapId() != PiratePqData.STAGE_1) {
            return lootIds.length == 0;
        }
        // Each round has its own cached array. Never use the new round's quota to
        // authorize a beat that was already aimed at the previous round's medal.
        return lootIds.length > 0 && lootIds == steppedOnLootIds()
                && PqActions.needsHuntForLeader(bot, lootIds[0], MEDALS_PER_ROUND);
    }

    @Override
    protected int[] steppedOnLootIds() {
        if (getChr().getMapId() == PiratePqData.STAGE_1) {
            int round = PqActions.readEimInt(getChr(), "stage2", -1);
            if (round >= 0 && round < MEDAL_LOOT_BY_ROUND.length) {
                return MEDAL_LOOT_BY_ROUND[round];
            }
        }
        return super.steppedOnLootIds();
    }

    @Override
    protected boolean workStage() {
        var map = getChr().getMap();
        if (map.getId() == PiratePqData.STAGE_1) {
            int[] medals = steppedOnLootIds();
            if (medals.length > 0) {
                PqActions.collectForLeader(getChr(), medals, MEDALS_PER_ROUND, STAGE_NPC);
            } else {
                PqActions.deliverAndWait(getChr(), MEDAL_LOOT_BY_ROUND[2][0], STAGE_NPC);
            }
            return false;
        }

        // Boxes first: in room 4 the monsters are inside them, so hitting boxes is also how
        // the fight starts. findReactorOid returns the first one still alive, so this walks
        // the room's boxes one at a time until none are left.
        for (int boxId : PiratePqData.boxesIn(map.getId())) {
            for (int guard = 0; guard < BOXES_PER_ROOM_LIMIT; guard++) {
                int oid = PqActions.findReactorOid(getChr(), boxId);
                if (oid < 0) {
                    break; // no more of this kind standing
                }
                if (!PqActions.hitReactor(getChr(), oid)) return false;
                if (!stillAlive(oid)) {
                    break; // this one is spent; the next pass finds the following one
                }
            }
        }

        // Then whatever the room has left standing.
        if (!map.getMonsters().isEmpty()) {
            PqActions.seekAndAttack(getChr());
        }
        // Hand any other quest items the bot picked up to the leader.
        for (int itemId : PiratePqData.QUEST_ITEMS) {
            PqActions.handItemsToLeader(getChr(), itemId);
        }
        return false;
    }

    /** How many of one box kind to keep hitting before giving the tick back. */
    private static final int BOXES_PER_ROOM_LIMIT = 24;

    private boolean stillAlive(int reactorOid) {
        var reactor = getChr().getMap().getReactorByOid(reactorOid);
        return reactor != null && reactor.isAlive();
    }
}
