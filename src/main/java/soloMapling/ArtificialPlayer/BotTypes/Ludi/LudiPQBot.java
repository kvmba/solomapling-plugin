package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.Environment.BotMessages;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Ludi PQ ("Dimensional Schism") with a real player leading.
 *
 * <p>Nine stages across as many maps, so the stage in play is worked out from the map the bot
 * is standing in - each stage has its own map here, unlike Orbis where several share one -
 * and whether that stage is already behind the party comes from the quest's own flags.
 *
 * <p>Most stages reduce to killing and gathering. The two that do not get their own handling:
 * the tower climb, which is a row of portals that mostly send the climber back down, and the
 * crate combination, where the quest has already written down which five of nine boxes it
 * wants filled and the bot reads that rather than trying arrangements.
 *
 * <p>The pass turn-ins stay with the player throughout: the stage NPCs check the inventory of
 * whoever is talking to them, and that is the leader.
 */
public class LudiPQBot extends PartyQuestBot {

    /** The lowest point this bot has reached on the current climb, to judge progress. */
    private int climbFloorY = Integer.MIN_VALUE;

    /** The stage whose plan was last announced, so the party is not spammed with it. */
    private int announcedStage = -1;

    /**
     * When this bot started waiting in the exit stage, or 0 while it is not there.
     *
     * <p>Arturo refuses to warp a body whose inventory has no room for the reward
     * ({@code 2040035.js}: the {@code else} branch only prints "make some room"), and the
     * event never ends after {@code 9stageclear} - its one-minute timer just moves the team
     * {@code 922011000 -> 922011100}. A bot that keeps retrying the conversation with a full
     * inventory would therefore sit in that room for the rest of the instance. Waiting is
     * still the right first answer (the player may be clearing a slot), so the retry is
     * bounded instead of removed: past {@link #EXIT_STAGE_GIVE_UP_MS} the bot leaves through
     * the normal lobby path, exactly as a body that walked out would.
     */
    private long exitStageSinceMs = 0L;

    /** How long the exit stage may refuse (a full inventory) before the bot gives up and leaves. */
    private static final long EXIT_STAGE_GIVE_UP_MS = 30_000L;

    public LudiPQBot(Character character) {
        super(character);
        dialoguePath = "LudiPQBotDialogue.yaml";
        botType = "LudiPQBot";
        questName = "LudiPQ";
        // The stage scripts resolve their speaker through client.getPlayer() when they run,
        // so this bot needs a client of its own rather than the shared per-channel one.
        BotGeneration.adoptPrivateClient(character);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        // Stage 5 stealth, for the WHOLE tick and both ways a bot reaches a sub-room: its own
        // claim-and-walk (workStage5FromMainRoom) and following the leader through a door
        // (followLeaderIntoNextRoom). Both cross 922010500's floor, which seats two invincible
        // 9300013 guards at y~54 while the bottom door (in06) sits at y=251 below them, and the
        // guard room itself holds seven. Raising the aura here - an action method, before any
        // movement - keeps it out of the isFollowingLeader() predicate, which only decides
        // whether a follow is allowed. A no-op outside stage 5 and for kits with no hide.
        int here = getChr().getMapId();
        if (LudiPqData.roomStage(here) == 5 || stageOf(here) == 5) {
            LudiStages.stayHidden(getChr());
        }
        tickPartyQuest();
    }

    @Override
    protected boolean isInsideQuest(int mapId) {
        // The run does not end at the boss. 922010900 is followed by the BONUS stage
        // (922011000, the reward-box map the event warps the team to) and the EXIT stage
        // (922011100, where Arturo hands out the reward and warps each body to town). Both
        // belong to the same event instance, so treating them as "outside the quest" is what
        // used to send every bot home the moment the boss died: the lobby branch in
        // tickPartyQuest fired before any stage work could see the bonus room.
        return mapId >= LudiPqData.ENTRY_MAP && mapId <= LudiPqData.EXIT_MAP;
    }

    @Override
    protected void releaseRoomState() {
        LudiStages.releaseRoomState(getChr().getId());
    }

    @Override
    protected int lobbyMapId() {
        return LudiPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        // The two maps after the boss are not stages: the event warps the team into the bonus
        // room (clearPQ) and the bonus room's own NPC warps each body on to the exit room. Both
        // are part of the run, and both have their own work - the reward boxes, then Arturo.
        int here = getChr().getMapId();
        if (here == LudiPqData.BONUS_MAP) {
            LudiStages.workBonusStage(getChr());
            return false;
        }
        if (here == LudiPqData.EXIT_MAP) {
            LudiStages.workExitStage(getChr());
            long now = System.currentTimeMillis();
            if (exitStageSinceMs == 0L) {
                exitStageSinceMs = now;
            } else if (now - exitStageSinceMs >= EXIT_STAGE_GIVE_UP_MS) {
                // Arturo will not warp a full inventory and the event never ends by itself
                // after 9stageclear - see EXIT_STAGE_GIVE_UP_MS.
                return true;
            }
            return false;
        }
        exitStageSinceMs = 0L; // left the exit stage (or never reached it in this run)
        // The door rooms (stages 4 and 5) sit just past their stage's main map, which the
        // stage arithmetic reads as stage 302+ - route them first, by room id. Both stages
        // explore autonomously: a bot in the main room claims a room and walks in through
        // its script portal; stage 5's guard rooms admit only hide-carrying lineages.
        int roomStage = LudiPqData.roomStage(getChr().getMapId());
        if (roomStage == 4) {
            LudiStages.workDoorRooms(getChr(), LudiPqData.STAGE4_ROOM_FIRST,
                    LudiPqData.STAGE4_ROOM_LAST);
            return false;
        }
        if (roomStage == 5) {
            LudiStages.sneakDoorRooms(getChr());
            return false;
        }

        int stage = stageOf(getChr().getMapId());
        if (stage < 0) {
            return false;
        }
        // Stages 4 and 5's main maps route into the door-room loops here (not only when
        // standing in a sub-room): a bot on 922010400/922010500 claims a room and walks in
        // on its own. The cleared check gates it - once the leader has turned the stage in,
        // the bots drop the errand and follow the party through the next door.
        if (stage == 4 && !LudiStages.stageCleared(getChr(), 4)) {
            LudiStages.workDoorRooms(getChr(), LudiPqData.STAGE4_ROOM_FIRST,
                    LudiPqData.STAGE4_ROOM_LAST);
            return false;
        }
        if (stage == 5 && !LudiStages.stageCleared(getChr(), 5)) {
            LudiStages.sneakDoorRooms(getChr());
            return false;
        }
        if (LudiStages.stageCleared(getChr(), stage)) {
            // Stage work is done: stand by the room's stage NPC instead of idling wherever
            // the last fight ended. Walking the portal is the leader's business, and the map
            // change re-homes this bot when it follows. The one exception: a bot still in
            // the stage-2 trap room - that room has no stage NPC and no next00, so waiting
            // there is a freeze; it takes its own exit back to the tower. The wait also IS
            // the delivery post: the stock of passes in this bot's pockets is dropped at
            // the leader's feet the moment he comes to the NPC for the turn-in.
            sayStageClearOnce(getChr().getMapId());
            if (getChr().getMapId() == LudiPqData.TRAP_ROOM) {
                LudiStages.exitTrapRoom(getChr());
                return false;
            }
            if (PqActions.handItemsToLeaderAfterStage(getChr(), LudiPqData.PASS,
                    LudiPqData.stageNpcId(stage)) > 0) {
                PqActions.say(getChr(), BotMessages.get("pq.passes_dropped"));
            }
            PqActions.waitNearStageNpc(getChr(), LudiPqData.stageNpcId(stage));
            // Stage 9 cleared does NOT end the run: the leader's trophy turn-in has the event
            // warp the whole team to the bonus stage (922011000), and the bonus stage's NPC
            // warps each body on to the exit stage (922011100). Reporting the run over here is
            // what made every bot vanish to the lobby the instant the boss died instead of
            // taking its reward. The run ends only when the exit stage's own reward warp puts
            // the bot back in the lobby, where isInsideQuest answers false.
            return false;
        }

        switch (stage) {
            case 1 -> workEntryRoom();
            case 2 -> {
                // The trap box warps the whole party into 922010201 - its four boxes carry
                // four of the stage's fifteen passes, so a bot trapped there works that room
                // (break, loot, exit back) instead of standing in it.
                if (getChr().getMapId() == LudiPqData.TRAP_ROOM) {
                    LudiStages.workTrapRoom(getChr());
                } else {
                    LudiStages.breakTowerBoxes(getChr());
                }
            }
            case 3 -> LudiStages.breakCratesAndHunt(getChr());
            case 7 -> LudiStages.gatherPasses(getChr(), stage);
            case 6 -> climb();
            case 8 -> standOnCrates();
            case 9 -> LudiStages.fightBoss(getChr());
            default -> { /* unreachable */ }
        }
        return false;
    }

    /**
     * The door-room loops drive this bot room by room on their own claims; the follow beat
     * must not pull it back to the leader's map mid-exploration (the leader stays on the
     * main map while the bots clear the rooms behind the doors). The stage-2 trap room never
     * follows out either: its own work and its own gated exit own the room. For the door
     * stages, two gates end the errand and restore normal following: the stage turned in
     * ("4stageclear"/"5stageclear" set), or every room personally cleared / held by a live
     * teammate - the bot parks at the NPC, delivers, and moves with the party again.
     */
    @Override
    protected boolean isFollowingLeader() {
        int mapId = getChr().getMapId();
        // The post-boss maps move by the EVENT's own warps (clearPQ sends the team to the bonus
        // room; the bonus NPC sends each body on), not by the leader walking a portal a bot can
        // follow through. There is no door to walk here, and the follow beat's forward-portal
        // fallback would have nothing to do - so let the stage work own these rooms.
        if (mapId == LudiPqData.BONUS_MAP || mapId == LudiPqData.EXIT_MAP) {
            return false;
        }
        if (mapId == LudiPqData.TRAP_ROOM) {
            // The trap room is a self-contained errand: the bot works its four pass boxes and
            // takes its own gated exit (exitTrapRoom) back to the tower. The follow beat must
            // not race that - its walk/enter cancels whatever the room's work has in flight (a
            // box approach, a pickup walk) and can pull the bot out with the room's four passes
            // still uncollected, which the stage needs to clear.
            return false;
        }
        int roomStage = LudiPqData.roomStage(mapId);
        int stage = stageOf(mapId);
        if (roomStage != 4 && roomStage != 5 && stage != 4 && stage != 5) {
            return true; // not a door-room stage: follow as usual
        }
        if (stage >= 0 && LudiStages.stageCleared(getChr(), stage)) {
            return true; // the stage is turned in - move with the party
        }
        // Stage 5: the posting table decides whether THIS bot may be where the party is
        // moving. A body with no hide must not be carried into the guard room (922010506)
        // - one PAD-999 touch kills it - and the leaders' door scripts admit any party
        // member, so the bot must not follow there; the quest's own next00 warp carries it
        // along instead. The leader's CURRENT room is the room the party is in.
        //
        // The stealth for this crossing is NOT raised here: this is a predicate, and the walk
        // it authorises is issued by the caller right after. updateState() raises it for every
        // stage-5 room before any movement happens, so both this follow walk and the bot's own
        // walk to a door are covered.
        if (roomStage == 5 || (stage == 5 && roomStage < 0)) {
            Character leader = partyLeader();
            int leaderStage = (leader != null) ? stageOf(leader.getMapId()) : -1;
            if (leaderStage == 5 && !LudiStages.stage5AdmitsTo(getChr(), leader.getMapId())) {
                return false;
            }
        }
        int roomFirst = roomStage == 5 ? LudiPqData.STAGE5_ROOM_FIRST : LudiPqData.STAGE4_ROOM_FIRST;
        int roomLast = roomStage == 5 ? LudiPqData.STAGE5_ROOM_LAST : LudiPqData.STAGE4_ROOM_LAST;
        if (!LudiStages.doorRoomsOutstanding(getChr(), roomFirst, roomLast)) {
            // This bot's door errand is done. The leader stepping into a sub-room is HIS
            // exploring, not a party move - trailing him there re-walks a cleared door and
            // then walks right back out. The NPC post is this bot's place.
            Character leader = partyLeader();
            if (leader != null && LudiPqData.roomStage(leader.getMapId()) >= 0) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * The combat beat must not swing while a stage-5 guard could be the target: swinging
     * breaks Dark Sight and the PAD-999 guards one-shot the bot out of it. The seek box
     * (not the tighter touch box) is the right gate here - a bot hunting killable mobs
     * from across the room must not open a fight that walks it into a guard, and a bot
     * at the NPC post has no swings to gate anyway (nothing to hunt, delivery holds it).
     */
    @Override
    protected boolean fightsOnSweep() {
        int mapId = getChr().getMapId();
        if (LudiPqData.roomStage(mapId) == LudiPqData.STAGE5_ROOM_LAST) {
            return false; // the guard room: hide is the mechanic
        }
        return !(stageOf(mapId) == 5 && LudiStages.nearbyGuardCount(getChr()) > 0);
    }

    /**
     * The pass is the item the whole quest is about, and every LPQ collection stage earns it by
     * killing at close range - so the combat beat scoops a pass the bot is standing on instead of
     * leaving it for the next macro tick. Stages whose passes come out of a box (2, 4, 5) get the
     * same treatment: those drops land at the box the bot is working, not across the room.
     *
     * <p>Stage 7 is omitted on purpose: its Rombard is summoned at a fixed spawn point and the
     * stage's own beat already walks to that drop; a stepped-on sweep there adds nothing.
     */
    @Override
    protected int[] steppedOnLootIds() {
        return new int[]{LudiPqData.PASS};
    }

    /**
     * Work the entry room like any other collection stage.
     *
     * <p>Stage 1 is not a waiting room: the Red Balloon (NPC 2040036) hands the door to
     * stage 2 only to the party leader carrying 25 passes, and until that conversation
     * happens the lpq0 portal refuses everyone. The bot kills the Ratz, loots the passes it
     * drops, and drops them at the leader's feet so he can make the turn-in - without that
     * the run can never leave this room.
     *
     * <p>Sources: {@code scripts/npc/2040036.js} (25 x 4001022, leader-only) and
     * {@code scripts/portal/lpq0.js} (refuses while "1stageclear" is unset).
     */
    private void workEntryRoom() {
        if (announcedStage != 1) {
            announcedStage = 1;
            soloMapling.ArtificialPlayer.PartyQuest.PqActions.say(getChr(),
                    BotMessages.get("pq.ludi.stage1"));
        }
        LudiStages.gatherPasses(getChr(), 1);
    }

    /**
     * Which stage a room is, by its position in the quest's map run.
     *
     * <p>The room ids step by HUNDRED (922010100, 922010200, ... 922010900), so the stage is
     * the id delta over 100 - the raw delta (the old arithmetic) returned 101 for stage 2 and
     * fell through the stage switch's default on every room past the first, which is why the
     * bots did nothing from stage 2 on.
     */
    private static int stageOf(int mapId) {
        if (mapId < LudiPqData.ENTRY_MAP || mapId > LudiPqData.STAGE_9) {
            return -1;
        }
        return (mapId - LudiPqData.ENTRY_MAP) / 100 + 1;
    }

    /**
     * Climb the tower, remembering the highest point reached.
     *
     * <p>The tower is a single map whose portals mostly drop the climber back to the bottom,
     * so "did that portal work" is answered by comparing heights across passes.
     */
    private void climb() {
        int hereY = getChr().getPosition().y;
        if (climbFloorY == Integer.MIN_VALUE) {
            climbFloorY = hereY;
        }
        boolean lifted = LudiStages.climbTower(getChr(), climbFloorY);
        if (lifted) {
            climbFloorY = Math.min(climbFloorY, getChr().getPosition().y);
        }
    }

    /**
     * Stand on this bot's share of the crates the quest asked for, and say which crates the
     * party still has to fill.
     *
     * <p>Exactly five bodies are needed and the player is one of them, so the plan is worth
     * announcing - without it the player is guessing at a puzzle the bots have half solved.
     */
    private void standOnCrates() {
        int wanted = LudiStages.takeCrate(getChr(), botIndex());
        if (wanted < 0) {
            return; // the quest has not picked the combination yet
        }
        if (announcedStage != 8) {
            announcedStage = 8;
            soloMapling.ArtificialPlayer.PartyQuest.PqActions.say(getChr(),
                    BotMessages.get("pq.ludi.stage8", wanted));
        }
    }

    /**
     * Which body this bot is among the bots in its party.
     *
     * <p>Counted from character ids, which is a stable order needing no setup and no
     * knowledge of the other bots. It keeps two Ludi bots off the same crate.
     */
    private int botIndex() {
        var party = getChr().getParty();
        if (party == null) {
            return 0;
        }
        int mine = getChr().getId();
        int ahead = 0;
        for (var member : party.getMembers()) {
            int id = member.getId();
            if (id != mine && id < mine && CharacterStorage.getBotById(id) != null) {
                ahead++;
            }
        }
        return ahead;
    }
}
