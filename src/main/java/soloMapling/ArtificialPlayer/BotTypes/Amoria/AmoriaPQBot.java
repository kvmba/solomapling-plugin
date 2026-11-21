package soloMapling.ArtificialPlayer.BotTypes.Amoria;

import org.gms.client.Character;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.ArtificialPlayer.PartyQuest.PqStageChat;

/**
 * Plays Amoria PQ ("Amorian Challenge") with a real player leading.
 *
 * <p>The quest's stage number is derived from the map, using the same arithmetic the NPC does,
 * so the bot always names the stage correctly without tracking anything.
 *
 * <p>Stages 2 and 3 are area puzzles on different maps: each records how many bodies each of its
 * areas should hold and compares that against where the party stands. The bot reads the published
 * combination and takes a spot from the far end, leaving the near slots for the player the quest
 * also counts. Stage 2 indexes the three areas of its sub-room; stage 3 the nine ropes on
 * {@code 670010400}.
 *
 * <p>Collection rooms deliver at Amos; the bonus stages keep the same bot in the run to work boxes.
 */
public class AmoriaPQBot extends PartyQuestBot {

    private static final int[] STATUE_PIECE_LOOT = {AmoriaPqData.STATUE_PIECE};
    private static final int[] MIRROR_LOOT = {4031595};
    private static final int[] BOSS_LOOT = {4031594};
    private static final int MARRIED_BONUS_MAP = 670010750;
    private static final int EXIT_MAP = 670011000;

    public AmoriaPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
        botType = "AmoriaPQBot";
        questName = "AmoriaPQ";
        // The stage NPC resolves its speaker through client.getPlayer() when it runs, so this
        // bot needs a client of its own rather than the shared per-channel one.
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
        return mapId >= AmoriaPqData.ENTRY_MAP && mapId <= AmoriaPqData.CLEAR_MAP || mapId == EXIT_MAP;
    }

    @Override
    protected int lobbyMapId() {
        return AmoriaPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean fightsOnSweep() {
        Character bot = getChr();
        int mapId = bot.getMapId();
        if (mapId == MARRIED_BONUS_MAP || mapId == AmoriaPqData.CLEAR_MAP) {
            return hasPlayerInRunRoom(mapId);
        }
        return switch (AmoriaPqData.stageOf(mapId)) {
            case 1 -> PqActions.readEimString(bot, "1stageclear") == null
                    && PqActions.needsHuntForLeader(bot, MIRROR_LOOT[0], 1);
            case 4 -> !collectionCleared()
                    && PqActions.needsHuntForLeader(bot, AmoriaPqData.STATUE_PIECE, AmoriaPqData.STATUE_PIECES_WANTED);
            case 6 -> PqActions.readEimString(bot, "6stageclear") == null
                    && PqActions.needsHuntForLeader(bot, BOSS_LOOT[0], 1);
            case 9 -> false;
            default -> true;
        };
    }

    @Override
    protected int[] steppedOnLootIds() {
        if (getChr().getMapId() == MARRIED_BONUS_MAP) return super.steppedOnLootIds();
        return switch (AmoriaPqData.stageOf(getChr().getMapId())) {
            case 1 -> MIRROR_LOOT;
            case 4 -> STATUE_PIECE_LOOT;
            case 6 -> BOSS_LOOT;
            default -> super.steppedOnLootIds();
        };
    }

    @Override
    protected boolean workStage() {
        int mapId = getChr().getMapId();
        if (mapId == EXIT_MAP) {
            settleRewardRoom(9201047);
            return false;
        }
        if (mapId == MARRIED_BONUS_MAP || mapId == AmoriaPqData.CLEAR_MAP) {
            boolean teammateHere = hasPlayerInRunRoom(mapId);
            if (teammateHere) {
                if (mapId == MARRIED_BONUS_MAP) {
                    breakBonusBoxes(6701000, 6701002);
                } else {
                    breakBonusBoxes(6702003, 6702012);
                }
            }
            return shouldLeaveBonusRoom(teammateHere);
        }
        int stage = AmoriaPqData.stageOf(mapId);
        return switch (stage) {
            case 1 -> stageOne();
            case 2, 3 -> ropePuzzle(stage);
            case 4 -> gatherRoom();
            case 6 -> {
                if (PqActions.readEimString(getChr(), "6stageclear") == null) {
                    PqActions.collectForLeader(getChr(), BOSS_LOOT, 1, 9201045);
                } else {
                    PqActions.deliverAndWait(getChr(), BOSS_LOOT[0], 9201045);
                }
                yield false;
            }
            case 5 -> fightRoom();
            default -> false; // past the run: nothing to add
        };
    }

    private void breakBonusBoxes(int first, int last) {
        Character bot = getChr();
        for (var box : bot.getMap().getAllReactors()) {
            if (box.getId() >= first && box.getId() <= last && box.isActive()
                    && PqActions.approachReactor(bot, box.getObjectId(), false, true) != PqActions.Approach.STUCK) {
                return;
            }
        }
    }

    @Override
    protected Portal forwardPortal(int leaderMapId) {
        if (getChr().getMapId() != MARRIED_BONUS_MAP || leaderMapId != AmoriaPqData.CLEAR_MAP) {
            return super.forwardPortal(leaderMapId);
        }
        return getChr().getMap().getPortals().stream()
                .filter(portal -> "stageBogo".equals(portal.getScriptName()))
                .min(java.util.Comparator.comparingDouble(portal ->
                        portal.getPosition().distanceSq(getChr().getPosition())))
                .orElse(null);
    }

    /** Collect only until Amos has been given the pieces or the leader is ready to turn them in. */
    private boolean gatherRoom() {
        if (!collectionCleared()) {
            PqActions.collectForLeader(getChr(), STATUE_PIECE_LOOT, AmoriaPqData.STATUE_PIECES_WANTED, 9201045);
        } else {
            PqActions.deliverAndWait(getChr(), AmoriaPqData.STATUE_PIECE, 9201045);
        }
        return false;
    }

    private boolean collectionCleared() {
        return PqActions.readEimString(getChr(), "4stageclear") != null;
    }

    /** A fight room the bot can help in without reading anything. */
    private boolean fightRoom() {
        PqActions.seekAndAttack(getChr());
        return false;
    }

    /**
     * Stage 1 is "shatter the mirror and bring the shard back" - the fight to get the shard is
     * the only part a bot can help with, and the turn-in wants the piece in the hand of
     * whoever talks to the NPC.
     */
    private boolean stageOne() {
        if (PqActions.readEimString(getChr(), "1stageclear") != null
                || !PqActions.needsHuntForLeader(getChr(), MIRROR_LOOT[0], 1)) {
            PqActions.deliverAndWait(getChr(), MIRROR_LOOT[0], 9201047);
            return false;
        }
        // The mirror on the hall floor: its drop is the pass item itself, and it is the one
        // thing in this stage the bots can reach (the mobs are behind gender doors).
        int mirror = PqActions.findReactorOid(getChr(), AmoriaPqData.MIRROR_REACTOR);
        if (mirror >= 0 && !PqActions.hitReactor(getChr(), mirror)) {
            return false;
        }
        PqActions.seekAndAttack(getChr());
        PqActions.loot(getChr(), getChr().getPosition(), 2_000, MIRROR_LOOT);
        return false;
    }

    /**
     * Stand on this bot's share of the rope pattern the party is submitting.
     *
     * <p>Both rope stages are placement puzzles judged with a bare yes/no, and both store their
     * answer in an instance property ({@code stage2combo} / {@code stage3combo}). The bot mostly
     * searches the candidate space instead ({@link PositionCombination}) and moves on only when a
     * talk provably judged the current pattern wrong; each round may also peek at the property,
     * starting at 0% on the first round and growing 0.5% per failed one.
     *
     * <p>Stage 3 is the nine-rope map {@code 670010400} (five of nine, one body each); stage 2 is
     * the three-area sub-room the stage-1 gate opens onto, whose generator draws a body COUNT per
     * area summing to five.
     *
     * <p>Always returns false: a rope stage is never the end of the run. The candidate space is
     * non-empty by construction, so returning "did I get a spot" here - as this used to - answered
     * the caller's "is the whole run over" question with yes on every tick, and the base class
     * walked the bot out of the instance the moment it reached stage 2.
     */
    private boolean ropePuzzle(int stage) {
        Character bot = getChr();
        if (PqActions.readEimString(bot, stage + "stageclear") != null) {
            sayStageClearOnce(stage);
            waitForAmos(bot, stage);
            return false;
        }
        String answerProperty = AmoriaPqData.comboPropertyFor(stage);
        var areas = AmoriaPqData.areasFor(stage);
        var plan = PositionCombination.standOn(bot, PositionCombination.botIndex(bot),
                stage == 2 ? AmoriaPqData.stage2Candidates() : AmoriaPqData.stage3Candidates(),
                areas, stage, answerProperty,
                PositionCombination.comboAnswer(answerProperty), () -> waitForAmos(bot, stage));
        PqStageChat.announcePositionPlan(this, stage, plan, areas);
        return false;
    }

    private static void waitForAmos(Character bot, int stage) {
        if (stage == 3) {
            PqActions.waitAtPuzzlePost(bot, AmoriaPqData.STAGE_3_WAIT_POST);
        } else {
            PqActions.waitNearPuzzleNpc(bot, AmoriaPqData.PUZZLE_NPC);
        }
    }
}
