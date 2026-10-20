package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.Environment.BotMessages;

import java.awt.Point;
import java.util.List;

import static soloMapling.ArtificialPlayer.BotHelpers.blockingSleep;

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
     * asked count, drop the surplus, only then take the grading talk. A surplus pile dropped
     * on the floor is re-lootable by the party, so over-hunting wastes nothing.
     *
     * @return true when the leader has the passes he needs, which is the stage's real bar
     */
    public static boolean workCoupons(Character bot) {
        if (PqActions.readEimString(bot, "1stageclear") != null) {
            return true;
        }
        // Already holding a pass: stop talking to Cloto. Every further click is graded against
        // the coupon count again, and a bot that keeps clicking spends the coupons it needs for
        // the answer (and, once a pass is in hand, keeps re-opening a conversation that can only
        // repeat itself). Hand the pass over and wait.
        if (PqActions.countItem(bot, KerningPqData.PASS) > 0) {
            PqActions.handItemsToLeader(bot, KerningPqData.PASS);
            PqActions.say(bot, BotMessages.get("pq.kerning.pass_dropped"));
            return false;
        }
        int asked = askedCoupons(bot);
        int held = PqActions.countItem(bot, KerningPqData.COUPON);
        if (asked > 0 && held > asked) {
            // Over the answer: the surplus would fail the grade. Drop it before the talk -
            // unaddressed so any party member can re-loot it.
            PqActions.dropSurplus(bot, KerningPqData.COUPON, held - asked);
            held = asked;
        }
        if (asked > 0 && held == asked) {
            // The exact count the question wants: take the grading talk.
            answerCloto(bot);
            return false;
        }
        if (asked == 0 && held > 0) {
            // Holding coupons but no question yet - take the question talk first (Cloto reads
            // the count only from the second status onward; carrying nothing keeps the grade
            // from firing short).
            answerCloto(bot);
            return false;
        }
        huntCoupons(bot);
        return false;
    }

    /**
     * How many coupons this bot's assigned question asks for, or 0 when Cloto has not yet given
     * the bot a question (grid slot -1).
     *
     * <p>The slot is the instance grid's own - the same map Cloto inserts on the first visit and
     * re-grades on every later one - so the count derived from it is the count she wants, not a
     * guess. A slot out of the question table's range (a script change) reads as unanswered
     * rather than inventing a count.
     */
    static int askedCoupons(Character bot) {
        int slot = PqActions.readEimGrid(bot);
        int[] answers = KerningPqData.QUESTION_ANSWERS;
        if (slot < 1 || slot > answers.length) {
            return 0;
        }
        return answers[slot - 1];
    }

    /**
     * Take Cloto's question and answer it, walking the conversation one click at a time.
     *
     * <p>Two clicks in one visit are needed: {@code NPCScriptManager.start} runs the script's
     * own {@code start()}, which itself calls {@code action(1,0,0)} - so the first talk only
     * reaches status 0, and the question/answer exchange needs one more.
     *
     * <p>The caller owns the count discipline: this is called either with no coupons (to take a
     * question) or with exactly the asked count (to be graded). Clicking with any other count
     * re-grades that count and short-changes the stage - the bug this conversation path replaced.
     */
    private static void answerCloto(Character bot) {
        if (PqActions.talkingTo(bot, KerningPqData.NPC_CLOTO)) {
            return; // a prompt is already open; let it finish before re-clicking
        }
        PqActions.talkTo(bot, KerningPqData.NPC_CLOTO, 0);
        blockingSleep(1_000);
        PqActions.talkTo(bot, KerningPqData.NPC_CLOTO, 0);
        if (PqActions.countItem(bot, KerningPqData.PASS) > 0) {
            PqActions.handItemsToLeader(bot, KerningPqData.PASS);
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
