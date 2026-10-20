package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the stage-1 coupon discipline: a bot must answer Cloto holding EXACTLY the count its
 * assigned question asks for.
 *
 * <p>The graded count is read off the instance grid - the same slot Cloto inserts on the first
 * visit and re-grades on every later one ({@code 9020001.js}: {@code eim.gridCheck} ->
 * {@code cm.itemQuantity(4001007) == stage1Answers[data-1]}). A bot that clicked her while
 * holding one coupon got a question, stopped hunting, and every later click re-graded that same
 * short count: the stage never cleared. These tests pin the pure half of the fix - the mapping
 * from grid slot to the count the bot must hoard - which is where a regression would reintroduce
 * the deadlock.
 */
class KerningCouponCountTest {

    /** Every question slot maps to the answer table, 1-based, exactly as Cloto grades it. */
    @Test
    void everyGridSlotMapsToItsQuestionAnswer() {
        int[] answers = {10, 35, 20, 25, 25, 30, 8};
        for (int i = 0; i < answers.length; i++) {
            assertEquals(answers[i], KerningPqData.QUESTION_ANSWERS[i],
                    "question slot " + (i + 1) + " must match the host's stage1Answers[" + i + "]");
        }
    }

    /**
     * The unanswered sentinel is zero, not -1: a bot with no question yet must go hunting (and
     * take the question talk), never "hoard -1" - a bot that treated the sentinel as a count
     * would consider itself done at zero coupons and click Cloto short-handed forever.
     */
    @Test
    void anUnassignedQuestionMeansZeroAndNeverBlocksTheHunt() {
        // askedCoupons is package-private and reads a live EIM, so the contract it implements is
        // pinned through the table it consults: slot 1..7 are answerable, anything else is 0.
        assertTrue(KerningPqData.QUESTION_ANSWERS.length == 7);
        for (int answer : KerningPqData.QUESTION_ANSWERS) {
            assertTrue(answer > 0, "every question must want a positive coupon count");
        }
    }

    /**
     * The counts must be reachable in one hunt: the smallest (8) and the largest (35) bound what
     * a bot has to carry, and both are under the stack size a single drop pile holds (a pile is
     * per-kill 1-3 coupons, so 35 is several kills' sweep, not an inventory trick).
     */
    @Test
    void theCountsSpanTheQuestionsTheHostAsks() {
        assertEquals(8, KerningPqData.QUESTION_ANSWERS[6], "the 7th question wants 8");
        assertEquals(35, KerningPqData.QUESTION_ANSWERS[1], "the 2nd question wants 35");
    }
}
