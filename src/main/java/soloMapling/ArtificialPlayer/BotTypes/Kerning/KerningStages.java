package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.Environment.BotMessages;

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

    static final int[] COUPON_LOOT_IDS = {KerningPqData.COUPON};

    /** The combat sweep only hunts while this member is short of its assigned coupon count. */
    static boolean needsCoupons(Character bot) {
        EventInstanceManager instance = bot.getEventInstance();
        if (instance == null || instance.getProperty("1stageclear") != null) {
            return false;
        }
        int question = instance.gridCheck(bot);
        return question > 0 && PqActions.countItem(bot, KerningPqData.COUPON) < requiredCoupons(question)
                && PqActions.countItem(bot, KerningPqData.PASS) == 0 && needsMemberPass(bot, instance);
    }

    /** Ask Cloto for a question, exchange the exact answer for a pass, and hand it over. */
    static void workCoupons(Character bot) {
        EventInstanceManager instance = bot.getEventInstance();
        if (instance == null || !needsMemberPass(bot, instance)) {
            return;
        }
        if (PqActions.countItem(bot, KerningPqData.PASS) == 0) {
            // Cloto records -1 before asking, a 1-based question index while collecting, and
            // 0 after awarding a pass. One talk runs the check and closes the conversation.
            int question = instance.gridCheck(bot);
            if (question == -1) {
                PqActions.talkTo(bot, KerningPqData.NPC_CLOTO);
                return;
            }
            if (question == 0) {
                return;
            }
            int excess = PqActions.countItem(bot, KerningPqData.COUPON) - requiredCoupons(question);
            if (excess < 0) {
                PqActions.approachRoomLoot(bot, COUPON_LOOT_IDS);
                return;
            }
            if (excess > 0 && !PqActions.dropStack(bot, KerningPqData.COUPON, excess, bot.getPosition())) {
                return;
            }
            PqActions.talkTo(bot, KerningPqData.NPC_CLOTO);
        }
        if (PqActions.handItemsToLeader(bot, KerningPqData.PASS) > 0) {
            PqActions.say(bot, BotMessages.get("pq.kerning.pass_dropped"));
        }
    }

    private static int requiredCoupons(int question) {
        return KerningPqData.QUESTION_ANSWERS[question - 1];
    }

    private static boolean needsMemberPass(Character bot, EventInstanceManager instance) {
        Character leader = instance.getLeader();
        return leader != null && leader != bot
                && PqActions.countItem(leader, KerningPqData.PASS) < instance.getPlayerCount() - 1;
    }

    // =========================================================================
    // Stages 2-4 - the area puzzles
    // =========================================================================

    /**
     * Stand where the party's current candidate says this bot belongs.
     *
     * <p>Stages 2-4 are rope/platform/barrel puzzles: the party has to put bodies on the right
     * areas and the quest answers only yes/no. The answer is stored in an instance property
     * ({@code stgNProperty}); the bot mostly searches the script's own row table
     * ({@link PositionCombination}) and moves on only when a talk has provably judged the
     * current row wrong, with each round allowed to peek at the property - 0% on the first and
     * 0.5% more after every failed one.
     *
     * <p>Returns a description of the plan so the caller can pass it on.
     */
    public static String takeMyPlace(Character bot, int stage, int botIndex) {
        var candidates = KerningPqData.candidatesFor(stage);
        String answerProperty = KerningPqData.answerPropertyFor(stage);
        PositionCombination.Plan plan = PositionCombination.standOn(bot, botIndex,
                System.identityHashCode(bot.getMap()),
                candidates, KerningPqData.areasFor(stage),
                stage, answerProperty,
                PositionCombination.indexAnswer(answerProperty, candidates));
        return describePlan(plan);
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
     *
     * <p>Spots are named by their position in the room's own layout ("第 2 处"), not by their
     * map coordinates: the coordinates this used to print were both untranslated and unreadable
     * in-game - a player cannot turn {@code (-721,-340)} into "the second rope from the left".
     * The number matches the order the room lists its ropes/platforms/barrels, which is the same
     * order {@code spotsFor} hands them to the search.
     */
    private static String describePlan(PositionCombination.Plan plan) {
        StringBuilder out = new StringBuilder();
        int total = plan.spots().size();
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                out.append(BotMessages.get("pq.kerning.spot_separator"));
            }
            out.append(BotMessages.get("pq.kerning.spot", i + 1));
        }
        return total > 0 ? out.toString() : null;
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
