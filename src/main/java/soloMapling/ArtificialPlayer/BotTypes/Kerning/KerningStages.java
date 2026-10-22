package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotClientBinding;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.Environment.BotMessages;

import java.awt.Point;
import java.util.List;

/**
 * What a bot does in each Kerning PQ stage.
 *
 * <p>Stage 1 is the odd one out: each non-leader member is given a question and has to show
 * up carrying exactly the number of coupons it asks for, and only the leader can spend the
 * passes that come back. A bot can do the coupon half, but the pass and the questions both
 * go through Cloto, and the leader is the one who hands them over, so the bot's job is to
 * gather and then step back rather than to push the stage through on its own.
 *
 * <p>Stages 2 through 4 are the same shape three times over: the quest has picked a row from
 * a fixed table (an instance property says which) and wants a specific number of people on
 * each rope, platform or barrel. The bot reads the row and stands where it says - the
 * placement the player is supposed to reach by trial and error.
 */
public final class KerningStages {

    private KerningStages() {
    }

    // =========================================================================
    // Stage 1 - coupons
    // =========================================================================

    /**
     * Gather the exact coupon count this bot's question asks for, then answer Cloto.
     *
     * <p>Stage 1 is the ladder every later stage stands on, and only the members can climb
     * it: Cloto hands a question to each member who talks to her (kept in the instance's grid
     * so it does not change on a re-visit) and gives a pass only to the member whose coupon
     * count matches it - the leader gets told "tell your party members to solve the questions",
     * and the leader's own talk can only hand in the passes.
     *
     * <p>The count must be EXACT at the moment of the answer, which is why the question is
     * read from the grid (the same slot Cloto grades against) rather than guessed: a bot that
     * clicked her while holding one coupon got a question, stopped hunting, and every later
     * click re-graded that same short count - the stage never cleared. The order below is
     * therefore: take the question first (one talk with no coupons), hunt to precisely the
     * asked count, drop the surplus, only then take the grading talk. The host's grid=0
     * completion state then keeps this bot out of the other members' coupon supply.
     *
     * @return true when the leader has the passes he needs, which is the stage's real bar
     */
    public static boolean workCoupons(Character bot) {
        if (PqActions.readEimString(bot, "1stageclear") != null) {
            return true;
        }
        PqActions.recoverUngatheredHandoffs(bot, KerningPqData.PASS);
        if (PqActions.countItem(bot, KerningPqData.PASS) > 0) {
            handPassToLeader(bot);
            return false;
        }
        int asked = askedCoupons(bot);
        int held = PqActions.countItem(bot, KerningPqData.COUPON);
        if (asked < 0) {
            answerCloto(bot, asked); // claim the question before collecting anything
            return false;
        }
        if (asked == 0) {
            // grid=0 is COMPLETE, not unanswered. Return any late coupons to the party.
            PqActions.dropSurplus(bot, KerningPqData.COUPON, held);
            PqActions.waitNearStageNpc(bot, KerningPqData.NPC_CLOTO);
            return false;
        }
        if (held >= asked) {
            answerCloto(bot, asked);
            return false;
        }
        huntCoupons(bot);
        return false;
    }

    /**
     * The host's three states: -1 unanswered, a positive coupon target, 0 completed.
     */
    static int askedCoupons(Character bot) {
        int slot = PqActions.readEimGrid(bot);
        int[] answers = KerningPqData.QUESTION_ANSWERS;
        if (slot <= 0) return slot;
        if (slot > answers.length) {
            throw new IllegalStateException("Unknown Cloto question: " + slot);
        }
        return answers[slot - 1];
    }

    /**
     * Cloto claims/grades at status 0 and disposes immediately. Starting once is the entire
     * exchange; starting again would reopen a different conversation against a changed grid.
     */
    private static void answerCloto(Character bot, int asked) {
        BotClientBinding.runWithBoundPlayer(bot, () -> {
            if (PqActions.talkingTo(bot, KerningPqData.NPC_CLOTO)) return;
            // The pet pickup uses this same client lock: it cannot add a late coupon
            // between trimming the count and the script's exact-quantity grade.
            if (asked > 0) {
                int held = PqActions.countItem(bot, KerningPqData.COUPON);
                if (held < asked) return;
                PqActions.dropSurplus(bot, KerningPqData.COUPON, held - asked);
                if (PqActions.countItem(bot, KerningPqData.COUPON) != asked) return;
            }
            PqActions.talkTo(bot, KerningPqData.NPC_CLOTO);
        });
        handPassToLeader(bot);
    }

    private static void handPassToLeader(Character bot) {
        if (PqActions.handItemsToLeader(bot, KerningPqData.PASS) > 0) {
            PqActions.say(bot, BotMessages.get("pq.kerning.pass_dropped"));
        }
    }

    private static void huntCoupons(Character bot) {
        PqActions.seekAndAttack(bot);
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{KerningPqData.COUPON});
    }

    // =========================================================================
    // Stages 2-4 - the area puzzles
    // =========================================================================

    /**
     * Stand where the quest's chosen row says this bot belongs.
     *
     * <p>Reads the row the quest stored rather than trying rows in turn, which is the whole
     * advantage of being able to read the instance - the player has to guess and wait for the
     * leader's feedback after each attempt.
     *
     * <p>Returns a description of the plan so the caller can pass it on, or null when the
     * quest has not picked a row yet (nobody has talked to Cloto on this stage, which is when
     * the pick happens).
     */
    public static String takeMyPlace(Character bot, int stage, int botIndex) {
        String published = PqActions.readEimString(bot, KerningPqData.answerPropertyFor(stage));
        int[] combination = KerningPqData.chosenCombination(stage, published);
        if (combination == null) {
            return null;
        }
        List<Point> spots = KerningPqData.spotsFor(stage);
        Point mine = KerningPqData.mySpotFor(combination, spots, botIndex);
        if (mine != null) {
            PqActions.holdArea(bot, mine, 1_200);
        }
        return describePlan(combination, spots);
    }

    /** Whether the stage has been cleared, by the quest's own per-stage flags. */
    public static boolean stageCleared(Character bot, int stage) {
        return PqActions.readEimString(bot, stage + "stageclear") != null;
    }

    /**
     * The plan in words, for the party chat.
     *
     * <p>Worth saying out loud: the quest counts every player in the instance including the
     * real one, so the player has to take the spots the bots left untouched. A bot that stayed
     * quiet would leave him guessing exactly as he would have without any help.
     */
    private static String describePlan(int[] combination, List<Point> spots) {
        StringBuilder out = new StringBuilder("spots needing someone: ");
        boolean any = false;
        for (int i = 0; i < combination.length && i < spots.size(); i++) {
            if (combination[i] <= 0) {
                continue;
            }
            if (any) {
                out.append(", ");
            }
            Point spot = spots.get(i);
            out.append("(").append(spot.x).append(",").append(spot.y).append(")");
            any = true;
        }
        return any ? out.toString() : null;
    }

    // =========================================================================
    // Stage 5 - the boss
    // =========================================================================

    /**
     * Fight the stage-five boss.
     *
     * <p>Nothing clever here: the stage ends when the mobs are down and the leader has the
     * passes, so the bot only has to add damage.
     */
    public static void fightBoss(Character bot) {
        PqActions.seekAndAttack(bot);
    }
}
