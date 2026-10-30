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
        tickPartyQuest();
    }

    /**
     * The shared 250ms combat beat, with this quest's reachability policy refreshed FIRST.
     *
     * <p>Stages 7 and 9 must only chase targets their graph can actually reach: stage 7's ledge
     * mobs and stage 9's altar Ratz both stand on shelves with no inbound edge. That intent used
     * to be expressed only inside {@code workStage}, which runs on the MACRO tick - 2-6s while a
     * player watches, but 36-48s on an unobserved map. The combat beat between those ticks runs
     * the no-arg {@code seekAndAttack}, i.e. reachableOnly=false, so a bot that entered
     * 922010900 before its first macro tick chased the altar ledge itself: measured, it walked
     * to (585,184) - the floor directly under the unreachable altar - and sat there until the
     * next macro tick happened to set the flag again (18.5s later in the same trace).
     *
     * <p>{@code applyChasePolicy} only writes the flag; it never issues a move. That is what makes
     * it safe to call before the beat's own gates (corpse, frozen, off-map) - a bot that is not
     * allowed to act still gets its policy corrected, and nothing is steered.
     */
    @Override
    public void grindTick() {
        LudiStages.applyChasePolicy(getChr());
        super.grindTick();
    }

    @Override
    protected boolean isInsideQuest(int mapId) {
        // The bonus rooms are part of the run: clearPQ warps the whole team to 922011000 for a
        // minute of reward boxes and then to 922011100. A bot that treats them as "outside the
        // quest" is warped home by tickLobby the instant the reward stage starts, which is why
        // the party lost its bots for the last minute of every run.
        return (mapId >= LudiPqData.ENTRY_MAP && mapId <= LudiPqData.STAGE_9)
                || mapId == LudiPqData.BONUS_MAP
                || mapId == LudiPqData.BONUS_TAIL_MAP;
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
        int mapId = getChr().getMapId();
        // The bonus rooms come FIRST: they are not a numbered stage (stageOf reads them as
        // 10+), and the whole point of the minute there is to break the reward boxes. Every
        // other stage path would route them to "stage cleared, wait by an NPC" and burn the
        // minute standing still.
        if (mapId == LudiPqData.BONUS_MAP) {
            LudiStages.breakBonusBoxes(getChr());
            return false;
        }
        // The tail room has NO boxes at all: it holds the reward NPC (2040035) and nothing
        // else. Sending it through the box loop left the bot standing there for the rest of
        // the run - and the room is inside this quest's map range, so tickLobby does not
        // reclaim it either. Its exit is the NPC beat: walk up, let the conversation "read",
        // then go home.
        if (mapId == LudiPqData.BONUS_TAIL_MAP) {
            if (LudiStages.takeBonusReward(getChr())) {
                returnToLobby("bonus stage finished");
            }
            return false;
        }
        // The door rooms (stages 4 and 5) sit just past their stage's main map, which the
        // stage arithmetic reads as stage 302+ - route them first, by room id. Both stages
        // explore autonomously: a bot in the main room claims a room and walks in through
        // its script portal; stage 5's guard rooms admit only hide-carrying lineages.
        int roomStage = LudiPqData.roomStage(mapId);
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
            // Stage 9 does NOT end the run: 2040044.js's turn-in calls eim.clearPQ(), which
            // warps the whole team to the bonus room for a minute of reward boxes. Returning
            // true here sent the bot home the moment the trophy was handed in, so every run
            // lost its bots for the bonus stage (and a party of only bots ended the instance).
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
        int mapId = getChr().getMapId();
        if (mapId == LudiPqData.BONUS_MAP || mapId == LudiPqData.BONUS_TAIL_MAP) {
            return new int[0]; // the bonus room's spray is the party's, not the bot's
        }
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
     * Which stage a room is, by its position in the quest's map run; -1 for any room that is not
     * part of the numbered run.
     *
     * <p>The room ids step by HUNDRED (922010100, 922010200, ... 922010900), so the stage is
     * the id delta over 100 - the raw delta (the old arithmetic) returned 101 for stage 2 and
     * fell through the stage switch's default on every room past the first, which is why the
     * bots did nothing from stage 2 on.
     *
     * <p>Shared with {@code LudiStages.applyChasePolicy} so the reachability rule and the work
     * routing can never disagree about which stage the bot is standing in.
     */
    static int stageOf(int mapId) {
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
                    wanted == 0
                            ? BotMessages.get("pq.ludi.stage8_full")
                            : BotMessages.get("pq.ludi.stage8", wanted));
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
