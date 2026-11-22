package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.Environment.BotMessages;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;

/**
 * What a bot does in each Kerning PQ stage.
 *
 * <p>Stage 1 is the odd one out: each non-leader member is given a question and has to show
 * up carrying exactly the number of coupons it asks for. Bots draw different coupon totals,
 * then use Cloto's own exchange and leave the passes for the leader to turn in.
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
    static final int[] PASS_LOOT_IDS = {KerningPqData.PASS};
    static final int BOSS_PASSES_WANTED = 10;
    private static final String COUPON_QUESTIONS = "soloMapling.kerning.couponQuestions";
    private static final int COLLECTION_CHAT_VARIANTS = 20;

    /** The combat sweep only hunts while this member is short of its assigned coupon count. */
    static boolean needsCoupons(Character bot, CouponRun run) {
        EventInstanceManager instance = bot.getEventInstance();
        if (instance == null || (run != null && run.instance == instance && !run.canCollect())
                || instance.getProperty("1stageclear") != null) {
            return false;
        }
        int question = instance.gridCheck(bot);
        return question > 0 && PqActions.countItem(bot, KerningPqData.COUPON) < requiredCoupons(question)
                && PqActions.countItem(bot, KerningPqData.PASS) == 0 && needsMemberPass(bot, instance);
    }

    /** This bot's visit to Cloto and collection announcements for one run. */
    static final class CouponRun {
        final EventInstanceManager instance;
        private long claimAtMs;
        private volatile long collectAtMs;
        private boolean completionAnnounced;

        CouponRun(EventInstanceManager instance) {
            this.instance = instance;
        }

        boolean canCollect() {
            long deadline = collectAtMs;
            return deadline == 0 || GCMovement.movementNowMs() >= deadline;
        }

        void work(Character bot, LongConsumer wake) {
            if (instance.getProperty("1stageclear") != null || !needsMemberPass(bot, instance)) {
                claimAtMs = 0;
                collectAtMs = 0;
                PqActions.deliverAndWait(bot, KerningPqData.PASS, KerningPqData.NPC_CLOTO);
                return;
            }
            if (PqActions.countItem(bot, KerningPqData.PASS) == 0) {
                // Cloto uses -1 before the question, a 1-based index during collection,
                // and 0 once she awards the pass.
                int question = instance.gridCheck(bot);
                if (question == -1) {
                    if (!readyToClaim(bot, wake)) return;
                    claimAtMs = 0;
                    question = reserveQuestion(bot, instance);
                    if (question == -1) return;
                    completionAnnounced = false;
                    collectAtMs = GCMovement.movementNowMs() + ThreadLocalRandom.current().nextLong(600, 3_001);
                    sayCollection(bot, "start", requiredCoupons(question));
                }
                if (question > 0) {
                    long now = GCMovement.movementNowMs();
                    if (now < collectAtMs) {
                        // Claim and departure are different actions. Wake on this member's own
                        // deadline; the party's macro/combat beats must not round them all together.
                        wake.accept(collectAtMs - now);
                        return;
                    }
                    int excess = PqActions.countItem(bot, KerningPqData.COUPON) - requiredCoupons(question);
                    if (excess < 0) {
                        return; // the combat sweep owns both pickup and hunting until the quota is met
                    }
                    announceCompletion(bot);
                    PqActions.stopHunting(bot);
                    if (!PqActions.walkToNpc(bot, KerningPqData.NPC_CLOTO)) return;
                    if (excess > 0 && !PqActions.dropStack(bot, KerningPqData.COUPON, excess, bot.getPosition())) {
                        return;
                    }
                    PqActions.talkTo(bot, KerningPqData.NPC_CLOTO);
                }
            }
            if (PqActions.deliverAndWait(bot, KerningPqData.PASS, KerningPqData.NPC_CLOTO) > 0) {
                PqActions.say(bot, BotMessages.get("pq.kerning.pass_dropped"));
            }
        }

        private boolean readyToClaim(Character bot, LongConsumer wake) {
            if (!PqActions.walkToNpc(bot, KerningPqData.NPC_CLOTO)) {
                claimAtMs = 0;
                return false;
            }
            long now = GCMovement.movementNowMs();
            if (claimAtMs == 0) {
                claimAtMs = now + ThreadLocalRandom.current().nextLong(2_000, 5_001);
            }
            if (now < claimAtMs) {
                wake.accept(claimAtMs - now);
                return false;
            }
            return true;
        }

        void pickedUp(Character bot) {
            int question = instance.gridCheck(bot);
            if (question <= 0) return;
            int remaining = requiredCoupons(question) - PqActions.countItem(bot, KerningPqData.COUPON);
            if (remaining > 0) {
                sayCollection(bot, "remaining", remaining);
            } else {
                announceCompletion(bot);
            }
        }

        private void announceCompletion(Character bot) {
            if (!completionAnnounced) {
                completionAnnounced = true;
                sayCollection(bot, "done");
            }
        }
    }

    /** Draw without replacement by coupon count, not question index: two questions ask for 25. */
    @SuppressWarnings("unchecked")
    private static int reserveQuestion(Character bot, EventInstanceManager instance) {
        synchronized (instance) {
            if (bot.getEventInstance() != instance || bot.getMapId() != KerningPqData.STAGE_1
                    || instance.getProperty("1stageclear") != null || !needsMemberPass(bot, instance)) {
                return -1;
            }
            Map<Integer, Integer> questions = (Map<Integer, Integer>) instance.getObjectProperty(COUPON_QUESTIONS);
            if (questions == null) {
                questions = new HashMap<>();
                instance.setObjectProperty(COUPON_QUESTIONS, questions);
            }
            // Cloto overwrites the grid after exchange, and unregistering a member clears it.
            // The same character must not draw a second quota if the host registers it again.
            int memberId = bot.getId();
            Integer question = questions.get(memberId);
            if (question == null) {
                BitSet used = new BitSet();
                for (int assigned : questions.values()) {
                    used.set(requiredCoupons(assigned));
                }
                int[] candidates = new int[KerningPqData.QUESTION_ANSWERS.length];
                int size = 0;
                for (int candidate = 1; candidate <= candidates.length; candidate++) {
                    if (!used.get(requiredCoupons(candidate))) {
                        candidates[size++] = candidate;
                    }
                }
                question = candidates[ThreadLocalRandom.current().nextInt(size)];
                questions.put(memberId, question);
            }
            instance.gridInsert(bot, question); // Cloto's normal script validates this index at exchange.
            return question;
        }
    }

    private static void sayCollection(Character bot, String beat, Object... args) {
        int variant = ThreadLocalRandom.current().nextInt(1, COLLECTION_CHAT_VARIANTS + 1);
        String line = BotMessages.getOr("pq.collection." + beat + "." + variant, "", args);
        if (!line.isBlank()) {
            SocialCommands.BotFullChat(bot, line);
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
     * <p>Returns the plan so the room's speaker can report the unfilled spots.
     */
    public static PositionCombination.Plan takeMyPlace(Character bot, int stage, int botIndex) {
        var candidates = KerningPqData.candidatesFor(stage);
        String answerProperty = KerningPqData.answerPropertyFor(stage);
        return PositionCombination.standOn(bot, botIndex, candidates,
                KerningPqData.areasFor(stage), stage, answerProperty,
                PositionCombination.indexAnswer(answerProperty, candidates),
                () -> PqActions.waitNearPuzzleNpc(bot, KerningPqData.NPC_CLOTO));
    }

    /** Whether the stage has been cleared, by the quest's own per-stage flags. */
    public static boolean stageCleared(Character bot, int stage) {
        return PqActions.readEimString(bot, stage + "stageclear") != null;
    }

    // =========================================================================
    // Stage 5 - the boss
    // =========================================================================

    static boolean needsBossPasses(Character bot) {
        return PqActions.needsHuntForLeader(bot, KerningPqData.PASS, BOSS_PASSES_WANTED);
    }
}
