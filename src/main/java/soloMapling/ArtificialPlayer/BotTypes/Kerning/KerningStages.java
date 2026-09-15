package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

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
     * Gather coupons and answer Cloto if the bot is one of the question-holders.
     *
     * <p>The answer is a count, and the only feedback the quest gives is whether it matched,
     * so the bot counts its own coupons against the published table. It leaves the pass it
     * earns in its own inventory: the leader is the one who turns passes in, and handing a
     * pass over is a trade the bot cannot do on its own.
     *
     * @return true when the leader has the passes he needs, which is the stage's real bar
     */
    public static boolean workCoupons(Character bot) {
        if (PqActions.readEimString(bot, "1stageclear") != null) {
            return true;
        }
        int coupons = PqActions.countItem(bot, KerningPqData.COUPON);
        if (coupons > 0) {
            // Enough is the only thing that matters; the excess does not help and holding it
            // makes the count check harder to reason about.
            return false;
        }
        huntCoupons(bot);
        return false;
    }

    private static void huntCoupons(Character bot) {
        PqActions.attack(bot);
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
        PqActions.attack(bot);
    }
}
