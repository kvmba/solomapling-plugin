package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;

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

    private static final long SETTLE_MS = 1_000;

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

    @Override
    protected boolean isInsideQuest(int mapId) {
        return mapId >= LudiPqData.ENTRY_MAP && mapId <= LudiPqData.STAGE_9;
    }

    @Override
    protected int lobbyMapId() {
        return LudiPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        int stage = stageOf(getChr().getMapId());
        if (stage < 0) {
            return false;
        }
        if (LudiStages.stageCleared(getChr(), stage)) {
            // The portal to the next stage is open; walking it is the leader's business, and
            // the map change re-homes this bot when it follows.
            return stage >= 9;
        }

        switch (stage) {
            case 1 -> workEntryRoom();
            case 2, 3, 4, 5, 7 -> LudiStages.gatherPasses(getChr(), stage);
            case 6 -> climb();
            case 8 -> standOnCrates();
            case 9 -> LudiStages.fightBoss(getChr());
            default -> { /* unreachable */ }
        }
        return false;
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
                    "Stage 1: collect 25 passes for the Red Balloon - the leader turns them in. I am fighting for them.");
        }
        LudiStages.gatherPasses(getChr(), 1);
    }

    /** Which stage a room is, by its position in the quest's map run. */
    private static int stageOf(int mapId) {
        if (mapId < LudiPqData.ENTRY_MAP || mapId > LudiPqData.STAGE_9) {
            return -1;
        }
        return mapId - LudiPqData.ENTRY_MAP + 1;
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
        sleep(SETTLE_MS);
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
                    "Stage 8: " + wanted + " crates need someone - I am on one, please take another.");
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
