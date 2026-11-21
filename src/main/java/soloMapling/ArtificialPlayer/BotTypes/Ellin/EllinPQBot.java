package soloMapling.ArtificialPlayer.BotTypes.Ellin;

import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.id.ItemId;
import org.gms.constants.id.MobId;
import org.gms.server.life.Monster;
import org.gms.server.maps.Portal;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.ArtificialPlayer.PartyQuest.PqStageChat;

import java.awt.Point;

/**
 * Plays Ellin PQ ("Ellin Forest") with a real player leading.
 *
 * <p>Use the forest's two item-triggered reactors, reach the maze's NPC, purify weakened
 * creatures without killing them, and bring the leader the stone that opens the altar.
 */
public class EllinPQBot extends PartyQuestBot {

    /** The NPC standing in the maze whose talk warps the team out of it. */
    private static final int MAZE_NPC = 2133001;
    private static final int FOREST_MAP = 930000200;
    private static final int STONE_MAP = 930000500;
    private static final int ALTAR_MAP = 930000600;
    private static final int STONE_NPC = 2133004;
    private static final int RAW_POISON = 4001161;
    private static final int DILUTED_POISON = 4001162;
    private static final int MAGIC_STONE = 4001163;
    private static final int[] POISON_LOOT = {RAW_POISON, DILUTED_POISON};
    private static final int[] STONE_LOOT = {MAGIC_STONE};

    public EllinPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
        botType = "EllinPQBot";
        questName = "EllinPQ";
        // The quest's reactor scripts resolve their player through client.getPlayer() when
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
        return EllinPqData.isQuestRoom(mapId);
    }

    @Override
    protected Portal forwardPortal(int leaderMapId) {
        var map = getChr().getMap();
        return map.getId() < EllinPqData.MAZE_MAP && leaderMapId >= map.getId() + 100
                ? map.getPortal("east00") : null;
    }

    @Override
    protected int lobbyMapId() {
        return EllinPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        Character bot = getChr();
        switch (bot.getMapId()) {
            case EllinPqData.ENTRY_MAP -> PqActions.waitNearStageNpc(bot, MAZE_NPC);
            case 930000700 -> settleRewardRoom(MAZE_NPC);
            case FOREST_MAP -> workForest(bot);
            case EllinPqData.MAZE_MAP -> workMaze(bot);
            case EllinPqData.FROG_ROOM -> {
                if (PqStageChat.isRoomSpeaker(this)) {
                    workFrogRoom(bot);
                } else {
                    PqActions.deliverAndWait(bot, ItemId.EPQ_MONSTER_MARBLE, MAZE_NPC);
                }
            }
            case STONE_MAP -> workStoneRoom(bot);
            case ALTAR_MAP -> {
                var instance = bot.getEventInstance();
                if (instance != null && instance.isEventCleared()) {
                    settleRewardRoom(MAZE_NPC);
                } else if (!feed(bot, 3001000, MAGIC_STONE)) {
                    PqActions.seekAndAttack(bot);
                }
            }
            case EllinPqData.LAST_ROOM -> settleExitPortal("east00");
            default -> {
                if (bot.getMap().getMonsters().isEmpty()) {
                    PqActions.waitNearStageNpc(bot, MAZE_NPC);
                } else {
                    PqActions.seekAndAttack(bot);
                }
            }
        }
        return false;
    }

    private static void workForest(Character bot) {
        if (PqActions.findReactorOid(bot, EllinPqData.SPINE_REACTOR) < 0) {
            PqActions.waitNearStageNpc(bot, MAZE_NPC);
        } else if (!feed(bot, EllinPqData.SPINE_REACTOR, DILUTED_POISON)
                && !feed(bot, 3002000, RAW_POISON)) {
            PqActions.seekAndAttack(bot, false, POISON_LOOT);
            PqActions.loot(bot, bot.getPosition(), 1_200, POISON_LOOT);
        }
    }

    private static boolean feed(Character bot, int reactorId, int itemId) {
        int oid = PqActions.findReactorOid(bot, reactorId);
        return oid >= 0 && PqActions.feedReactor(bot, oid, itemId);
    }

    private static void workMaze(Character bot) {
        var map = bot.getMap();
        var portals = map.getPortals();
        Point position = bot.getPosition();
        Portal cell = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Portal portal : portals) {
            String name = portal.getName();
            if (name.length() != 4 || !name.endsWith("st") || portal.getTargetMapId() != map.getId()) continue;
            double distance = position.distanceSq(portal.getPosition());
            if (distance < nearest) {
                cell = portal;
                nearest = distance;
            }
        }
        if (cell == null || "16st".equals(cell.getName())) {
            PqActions.talkTo(bot, MAZE_NPC, 0);
            return;
        }
        // Every exit in a cell runs the same script. It may send us to the NPC cell
        // or the next cell, so walk to a reachable exit and let the script roll.
        String script = "party6_stage5" + cell.getName().substring(0, 2);
        Portal exit = null;
        nearest = Double.POSITIVE_INFINITY;
        for (Portal portal : portals) {
            if (!script.equals(portal.getScriptName()) || !portal.getPortalStatus()) continue;
            Point approach = GCMovement.portalApproachPoint(map, portal);
            double distance = position.distanceSq(approach);
            if (distance < nearest && GCMovement.canReachOnFoot(bot, approach.x, approach.y)) {
                exit = portal;
                nearest = distance;
            }
        }
        if (exit != null) PqActions.walkThroughPortal(bot, exit);
    }

    private static void workStoneRoom(Character bot) {
        if (!PqActions.needsHuntForLeader(bot, MAGIC_STONE, 1)) {
            PqActions.deliverAndWait(bot, MAGIC_STONE, STONE_NPC);
            return;
        }
        PqActions.loot(bot, bot.getPosition(), 1_200, STONE_LOOT);
        int box = PqActions.findReactorOid(bot, 3002001);
        if (box >= 0) PqActions.approachReactor(bot, box, false, true);
    }

    private static void workFrogRoom(Character bot) {
        var instance = bot.getEventInstance();
        if (instance == null) return;
        int marble = ItemId.EPQ_MONSTER_MARBLE;
        if (!PqActions.needsHuntForLeader(bot, marble, 20)) {
            PqActions.deliverAndWait(bot, marble, MAZE_NPC);
            return;
        }
        var position = bot.getPosition();
        Monster target = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Monster monster : bot.getMap().getAllMonsters()) {
            if (!canPurify(monster)) continue;
            double distance = position.distanceSq(monster.getPosition());
            if (distance < nearest) {
                target = monster;
                nearest = distance;
            }
        }
        if (target == null) {
            PqActions.deliverAndWait(bot, marble, MAZE_NPC);
            return;
        }
        int purifier = ItemId.EPQ_PURIFICATION_MARBLE;
        if (PqActions.countItem(bot, purifier) == 0) {
            // The first visit explains the job, the next prompt issues ten purifiers.
            if (instance.gridCheck(bot) == 1) {
                PqActions.talkTo(bot, MAZE_NPC);
            } else {
                PqActions.talkTo(bot, MAZE_NPC, 0);
            }
            return;
        }
        if (PqActions.walkTo(bot, target.getPosition()) && canPurify(target)
                && target.getMap() == bot.getMap()
                && bot.canHold(marble, 1)
                && InventoryManipulator.addById(bot.getClient(), marble, (short) 1)) {
            InventoryManipulator.removeById(bot.getClient(), InventoryType.USE, purifier, 1, true, true);
            bot.getMap().broadcastMessage(PacketCreator.catchMonster(target.getObjectId(), purifier, (byte) 1));
            bot.getMap().killMonster(target, null, false);
        }
    }

    private static boolean canPurify(Monster monster) {
        return monster.getId() == MobId.POISON_FLOWER && monster.isAlive()
                && monster.getHp() < monster.getMaxHp() / 10 * 4;
    }

    /**
     * The combat beat must not fight here either: the frog room's guards die to ordinary
     * swings and have to be purified with the NPC's item instead, so a bot that "helps" by
     * swinging on the sweep would kill what the party came to catch.
     */
    @Override
    protected boolean fightsOnSweep() {
        return switch (getChr().getMapId()) {
            case EllinPqData.FROG_ROOM, 930000700, EllinPqData.LAST_ROOM -> false;
            default -> true;
        };
    }
}
