package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The honest search for a body-placement puzzle: the party puts bodies on areas and the quest
 * answers only yes/no.
 *
 * <p>Five quest stages ship this shape and they differ only in what one "pattern" is:
 * <ul>
 *   <li>Ludi stage 8 - five of nine crates, one body per crate;</li>
 *   <li>Kerning stages 2-4 - a row of the script's own table;</li>
 *   <li>Amoria stage 3 - five of nine ropes, one body per area;</li>
 *   <li>Amoria stage 2 and Orbis stage 4 - a body COUNT per area, carried in the pattern's
 *       nibbles instead of single bits.</li>
 * </ul>
 * Every one of them picks the answer when the leader first talks to the stage NPC, stores it in
 * an instance property, and compares it against the party's placement on every later talk; a miss
 * is {@code showWrongEffect()} and nothing else - no hint about which area was wrong.
 *
 * <p>Reading that property outright would turn the stage into a lookup, which is not the puzzle a
 * player plays, so the search walks the candidates in a fixed order and retires one only when a
 * talk has provably judged it wrong; see {@link #judgedPattern}. Each round may additionally
 * peek at the answer: the first round never does, and every pattern already retired adds 0.5%
 * to the next round's chance - see {@link #peekChance} and {@link #peekedIndex}.
 *
 * <p>State is per room, not per bot: every bot in the room stands on its share of the same
 * candidate, so they must all see the same ledger.
 */
public final class PositionCombination {

    /**
     * How long the party has to have been standing on the presented pattern before a talk counts
     * as its verdict. A candidate change sends every bot walking; a talk landing mid-walk judged
     * whatever happened to be standing then, not the pattern the ledger is about to retire.
     */
    private static final long SETTLE_MS = 2_500L;

    /**
     * How much each failed round adds to the next round's chance of peeking at the answer:
     * round {@code n} (1-based) peeks with probability {@code (n - 1) * 0.5%}, so the first
     * round never does and a long search keeps growing without a cap.
     */
    static final double PEEK_STEP = 0.005;

    /** One room's search: what is ruled out, what is presented, and what the stage has told us. */
    static final class Ledger {
        final Set<Long> tried = new HashSet<>();
        /**
         * The round whose peek has been rolled. The round is {@code tried.size() + 1}, so this
         * key is what keeps the roll to ONE per round instead of once per tick.
         */
        int peekRound = -1;
        /** The candidate the current round presents because it peeked, or {@code -1}. */
        int peekIndex = -1;
        /** Index of the candidate being presented. */
        int index;
        /** The pattern presented, and when the party was first seen standing on it. */
        long presentedPattern = -1;
        /** The pattern seen on the latest observation, and how many bodies it counted. */
        long currentPattern = -1;
        /** When the current pattern was first seen (see {@link #SETTLE_MS}). */
        long firstSeenAtMs;
        int currentBodies;
        /**
         * Whether the stage has already answered at least once, i.e. whether a talk can be
         * judged at all. The first talk in a stage is the intro: it picks the answer and explains
         * the rules, so nothing is being judged. Every talk after it is a verdict attempt.
         */
        boolean stageAnswered;
        /** The leader whose talks are being watched; a handover restarts the observation. */
        int leaderId = Integer.MIN_VALUE;
        /** The leader's NPC clock at the last sample: an advance means a talk just finished. */
        long leaderTalkAt = Long.MIN_VALUE;
        /** The stage this ledger is watching; a stage change restarts the observation. */
        int stage = Integer.MIN_VALUE;
        private boolean sampled;
    }

    private static final Map<Integer, Ledger> LEDGERS = new ConcurrentHashMap<>();

    /** One candidate: the packed pattern, and where each body stands. */
    public record Candidate(long pattern, List<Point> spots) {
    }

    /** The candidate the party is submitting, plus what to say about it. */
    public record Plan(List<Point> spots, int free, int attempt) {
    }

    /**
     * The answer a stage published, as its packed pattern, or a non-positive value when this
     * bot cannot read it (no instance, no answer yet, a shape the parser does not know).
     */
    @FunctionalInterface
    public interface Answer {
        long patternOf(Character bot);
    }

    private PositionCombination() {
    }

    /**
     * The answer encoded as one comma-separated property - Ludi's {@code stage8combo}, Amoria's
     * {@code stage2combo}/{@code stage3combo} - packed into the nibble form candidates use.
     *
     * <p>A missing or non-numeric value yields {@code -1}; the stages themselves blank the
     * property when they discard an answer, so this is a real state, not a defensive check.
     */
    public static Answer comboAnswer(String property) {
        return bot -> {
            String published = PqActions.readEimString(bot, property);
            if (published == null || published.isBlank()) {
                return -1;
            }
            String[] parts = published.split(",");
            int[] counts = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    counts[i] = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            return countsToPattern(counts);
        };
    }

    /** The answer encoded as one integer per property - Orbis stage 4's {@code stage4_0..2}. */
    public static Answer countsAnswer(String... properties) {
        return bot -> {
            int[] counts = new int[properties.length];
            for (int i = 0; i < properties.length; i++) {
                String published = PqActions.readEimString(bot, properties[i]);
                if (published == null || published.isBlank()) {
                    return -1;
                }
                try {
                    counts[i] = Integer.parseInt(published.trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            return countsToPattern(counts);
        };
    }

    /** The answer encoded as an index into the candidate list - Kerning's {@code stgNProperty}. */
    public static Answer indexAnswer(String property, List<Candidate> candidates) {
        return bot -> {
            String published = PqActions.readEimString(bot, property);
            if (published == null || published.isBlank()) {
                return -1;
            }
            int index;
            try {
                index = Integer.parseInt(published.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
            return index >= 0 && index < candidates.size() ? candidates.get(index).pattern() : -1;
        };
    }

    /** Drop one room's search (the party left the stage, or the run ended). */
    public static void release(int roomKey) {
        LEDGERS.remove(roomKey);
    }

    /**
     * Stand on this body's share of the candidate being submitted, and report the plan.
     *
     * @param bot        the body taking its share
     * @param bodyIndex  its rank among the bots, for the tail-first split
     * @param roomKey    one instance's puzzle room
     * @param candidates every pattern the puzzle can ask for, in the order to try them
     * @param areas      the room's area rectangles, in the script's own order
     * @param stage      the stage number, for the {@code <stage>stageclear} flag
     * @param answerProperty the instance property the stage stores its answer in. Only its
     *     PRESENCE is used - it being set means the intro talk happened, so a later talk is a
     *     real verdict. Its value is never read by this path, so it carries no peek.
     * @param answer     how to read the answer for the round's peek, or {@code null} to
     *     search honestly in every round
     */
    public static Plan standOn(Character bot, int bodyIndex, int roomKey,
                               List<Candidate> candidates, List<Rectangle> areas, int stage,
                               String answerProperty, Answer answer) {
        Plan plan = decide(bot, roomKey, candidates, areas, stage, answerProperty,
                seatedBots(bot), answer);
        Point mine = mine(plan.spots(), bodyIndex);
        if (mine != null) {
            PqActions.holdArea(bot, mine, 1_200);
        }
        return plan;
    }

    /**
     * The decision half of {@link #standOn}, with no movement: observe the room, fold in any
     * verdict, and pick the candidate to present. Split out so the judgement wiring is testable
     * without a navigation graph behind the map (the tests have none).
     *
     * <p>The peek is rolled by {@link #plan}, once per round. The round is the same attempt number
     * the plan reports - {@code tried.size() + 1} - so round 1 is a hard 0% and every verdict that
     * retires a pattern moves the chance one step of 0.5%.
     */
    public static Plan decide(Character bot, int roomKey, List<Candidate> candidates,
                              List<Rectangle> areas, int stage, String answerProperty,
                              int seated, Answer answer) {
        Ledger ledger = LEDGERS.computeIfAbsent(roomKey, ignored -> new Ledger());
        synchronized (ledger) {
            observeJudgement(bot, ledger, areas, stage, answerProperty);
            return plan(ledger, candidates, seated, bot, answer);
        }
    }

    /**
     * The chance that round {@code round} (1-based) peeks at the answer instead of searching:
     * {@code (round - 1) * 0.5%}, uncapped, and clamped to certainty.
     */
    static double peekChance(int round) {
        return Math.min(1.0, Math.max(0, round - 1) * PEEK_STEP);
    }

    /**
     * Whether a roll wins the round's peek. {@code roll} is uniform in [0, 1).
     *
     * <p>Pure so the hard zero is testable: at round 1 the chance is 0%, so no roll can win -
     * not even the smallest representable one.
     */
    static boolean peekWins(int round, double roll) {
        return roll < peekChance(round);
    }

    /**
     * The candidate the answer names, or {@code -1} when the peek cannot be presented.
     *
     * <p>Two things disqualify it: no candidate has that pattern (a stale property from another
     * run, a shape this puzzle never asks for), and the pattern is one a talk already judged
     * wrong. Presenting the latter would fail again and never advance the round, so the party
     * would loop forever - the honest walk has to carry the round instead.
     */
    static int peekedIndex(List<Candidate> candidates, Set<Long> tried, long answerPattern) {
        for (int i = 0; i < candidates.size(); i++) {
            long pattern = candidates.get(i).pattern();
            if (pattern == answerPattern && !tried.contains(pattern)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The pattern a just-ended talk judged wrong, or {@code -1} when the evidence is not
     * conclusive.
     *
     * <p>This is the whole safety story, kept pure so the "never blame the wrong pattern"
     * invariants are testable without a live map. A talk retires the presented pattern only when
     * every one of these holds - each is a way the judged pattern could otherwise differ from the
     * one the bots believe they are presenting:
     * <ul>
     *   <li>the stage has answered before, so this was not the intro talk (which picks the answer
     *       and judges nothing);</li>
     *   <li>the stage is not clear, so the talk really was a miss and not the winning one;</li>
     *   <li>the room is standing on the presented pattern right now, and has been for the settle
     *       window - so a talk that lands while the bots are still walking judges nothing;</li>
     *   <li>the bodies actually counted match what the presented pattern needs. Two people in one
     *       area leave a mask-looking pattern intact but still fail the script's count; retiring
     *       then would throw away a correct answer.</li>
     * </ul>
     *
     * @param bodies          bodies the room counted on this sample
     * @param bodiesInPattern bodies the presented pattern asks for
     * @return the pattern to retire, or -1 to keep it in play
     */
    public static long judgedPattern(long presentedPattern, int bodies, int bodiesInPattern,
                                     long standingSinceMs, long settleMs,
                                     boolean judgedBefore, boolean cleared) {
        boolean settled = presentedPattern > 0
                && bodiesInPattern > 0
                && bodies == bodiesInPattern
                && standingSinceMs >= settleMs;
        return settled && judgedBefore && !cleared ? presentedPattern : -1;
    }

    /** Whether the quest says this stage is behind the party. */
    static boolean stageCleared(Character bot, int stage) {
        return PqActions.readEimString(bot, stage + "stageclear") != null;
    }

    /**
     * Feed one observation of the room's pattern into the ledger.
     *
     * <p>Keeps the last pattern actually seen and the moment it started being seen. The settle
     * window is measured from there - from when the party is standing on the pattern - and not
     * from when the candidate was chosen, which would let a talk that lands while everyone is
     * still walking count as a verdict on the destination.
     */
    static void sampleWindow(Ledger ledger, long pattern, int bodies) {
        if (pattern != ledger.currentPattern || ledger.firstSeenAtMs == 0L) {
            ledger.currentPattern = pattern;
            ledger.firstSeenAtMs = System.currentTimeMillis();
        }
        ledger.currentBodies = bodies;
    }

    /**
     * Begin watching a (leader, stage) pair. A new leader or a new stage restarts the
     * observation: the new stage's intro is again a first talk, which judges nothing.
     */
    static void startWatching(Ledger ledger, int leaderId, int stage, long talkAt) {
        ledger.sampled = true;
        ledger.leaderId = leaderId;
        ledger.stage = stage;
        ledger.leaderTalkAt = talkAt;
        ledger.stageAnswered = false;
    }

    /**
     * Fold one tick of the stage's state into the ledger.
     *
     * <p>The judgement signal needs care, because the obvious ones are wrong. The leader's NPC
     * clock ({@code getNpcCooldown}) advances on EVERY finished conversation - including the
     * leave-map NPC standing in the same room - so it cannot distinguish a verdict from a
     * goodbye. What does: the stage scripts publish a per-stage answer property as soon as the
     * intro talk happens, and every verdict talk needs that property to already exist. The
     * property NAME is supplied by the quest, but its VALUE is never read - only whether the
     * stage has answered, which is the precondition for any judgement, not the answer itself.
     */
    private static void observeJudgement(Character bot, Ledger ledger, List<Rectangle> areas,
                                         int stage, String answerProperty) {
        Character leader = PqActions.partyLeader(bot);
        if (leader == null || leader.getMapId() != bot.getMapId()) {
            return; // only a talk in this room, by the body the scripts listen to, can judge
        }
        PqActions.Pattern standing = PqActions.observedPattern(bot, areas);
        sampleWindow(ledger, standing.pattern(), standing.bodies());
        long talkAt = leader.getNpcCooldown();
        if (!ledger.sampled || ledger.leaderId != leader.getId() || stage != ledger.stage) {
            startWatching(ledger, leader.getId(), stage, talkAt);
            return;
        }
        boolean answered = stageAnswered(bot, answerProperty);
        if (answerWasDiscarded(ledger.stageAnswered, answered)) {
            // The stage threw its answer away and will pick a NEW one (Amoria stage 2 does this
            // every seven misses: stage2combo is blanked, then regenerated on the next talk).
            // Everything ruled out so far was ruled out against the OLD answer, so the whole
            // search restarts - keeping the ledger would skip candidates that may now be right.
            resetSearch(ledger);
        }
        if (!ledger.stageAnswered) {
            // Until an answer exists nothing can have been judged. Only its presence is checked -
            // never its value.
            if (!answered) {
                return;
            }
            // The talk that created it WAS the intro, so judging starts from the next one.
            ledger.stageAnswered = true;
            ledger.leaderTalkAt = talkAt;
            ledger.firstSeenAtMs = System.currentTimeMillis();
            return;
        }
        if (!talkFinished(ledger.leaderTalkAt, talkAt)) {
            return; // no conversation finished since the last sample: nothing was judged
        }
        ledger.leaderTalkAt = talkAt;
        if (ledger.currentPattern != ledger.presentedPattern) {
            return; // the room is not on the candidate, so nothing about it was judged
        }
        if (ledger.firstSeenAtMs == 0L) {
            return; // the party has not been seen standing on this candidate yet
        }
        long standingFor = System.currentTimeMillis() - ledger.firstSeenAtMs;
        long retired = judgedPattern(ledger.presentedPattern, ledger.currentBodies,
                PqActions.bodiesOf(ledger.presentedPattern),
                standingFor, SETTLE_MS, true, stageCleared(bot, stage));
        if (retired > 0) {
            ledger.tried.add(retired);
        }
    }

    /**
     * Whether the stage has discarded the answer it previously published.
     *
     * <p>This is the trigger for {@link #resetSearch}: a stage that had an answer and no longer
     * has one is about to generate a different one (Amoria stage 2 blanks {@code stage2combo}
     * every seven misses). Ruled-out candidates belong to the old answer and must not survive.
     */
    static boolean answerWasDiscarded(boolean hadAnswer, boolean hasAnswer) {
        return hadAnswer && !hasAnswer;
    }

    /**
     * Throw the whole search away, because the stage discarded its answer and will pick a new one.
     *
     * <p>Amoria stage 2 does exactly this every seven misses: it blanks {@code stage2combo} and
     * regenerates a different combination on the next talk. Everything ruled out so far was
     * ruled out against the OLD answer, so carrying the ledger over would skip candidates that
     * may now be the answer - and the stage could never be finished.
     */
    static void resetSearch(Ledger ledger) {
        // The round is derived from the ruled-out set, so clearing it puts the next submission
        // back on round 1 (a hard 0%); the roll key goes with it.
        ledger.tried.clear();
        ledger.index = 0;
        ledger.peekRound = -1;
        ledger.peekIndex = -1;
        ledger.presentedPattern = -1;
        ledger.firstSeenAtMs = 0L;
        ledger.stageAnswered = false;
    }

    /**
     * Whether a conversation finished since the last sample.
     *
     * <p>Needed ALONGSIDE {@code stageAnswered}: without it the ledger would treat every tick as
     * a verdict, and a party that simply stood still would burn one candidate per tick until the
     * space was empty. The clock advances on any finished conversation, so this answers exactly
     * one question - "did something just happen?" - and the stage-answered gate separates the
     * stage NPC's verdict from the leave-map NPC's goodbye.
     */
    static boolean talkFinished(long lastTalkAt, long talkNow) {
        return talkNow > lastTalkAt;
    }

    /**
     * Whether the stage has already published its answer (its value is never read here).
     *
     * <p>An EMPTY value counts as "not answered": Amoria stage 2 blanks {@code stage2combo} with
     * {@code ""} when it discards a wrong combination, so a null check alone would read the
     * discarded state as still answered and {@link #resetSearch} would never fire.
     */
    static boolean stageAnswered(Character bot, String answerProperty) {
        String published = answerProperty == null
                ? null
                : PqActions.readEimString(bot, answerProperty);
        return published != null && !published.isBlank();
    }

    /** The first candidate from {@code from} that no judged talk has ruled out, wrapping once. */
    public static int nextUntried(Set<Long> tried, List<Candidate> candidates, int from) {
        for (int step = 0; step < candidates.size(); step++) {
            int index = Math.floorMod(from + step, candidates.size());
            if (!tried.contains(candidates.get(index).pattern())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Pick the candidate the party presents, rolling the peek when a new round starts.
     *
     * <p>A round is one candidate the party actually submits, and the round number counts
     * PRESENTATIONS - deliberately not verdicts. The judge may swallow a real submission (the
     * talk that first publishes the answer is treated as the intro, and some stages both publish
     * and judge in that same talk), so counting verdicts would leave the second submission at
     * round 1 and keep its chance at 0%. Counting here keeps "round 1 = 0%" exact for every
     * quest regardless of how its own script phrases the intro.
     *
     * <p>The roll happens when a round ends, which is the only moment the next candidate is
     * chosen - never once per tick.
     *
     * <p>A peeked candidate is used only while it is unretired, and it never writes to
     * {@code tried}: the honest walk keeps its own order, so the party can never lose a candidate
     * it has not presented honestly.
     */
    static Plan plan(Ledger ledger, List<Candidate> candidates, int seated,
                     Character bot, Answer answer) {
        int honest = nextUntried(ledger.tried, candidates, ledger.index);
        if (honest < 0) {
            // Every pattern ruled out means the ledger, not the quest, is wrong (a stale entry
            // from an interrupted run). Sweeping again is the only honest move left.
            ledger.tried.clear();
            honest = nextUntried(ledger.tried, candidates, 0);
        }
        ledger.index = honest;
        // The round is the attempt the party is on - the same number the plan already reports
        // and the chat line already speaks ({@code tried.size() + 1}). Using it as the peek's
        // input keeps one definition of "round" for the whole feature.
        int round = ledger.tried.size() + 1;
        if (ledger.peekRound != round) {
            // One roll per round: a party standing on a candidate must not re-roll each tick.
            ledger.peekRound = round;
            ledger.peekIndex = peekedIndex(bot, ledger, candidates, round, answer);
        }
        // A peek is spent by its round; once a talk retires it, the honest walk carries on.
        if (ledger.peekIndex >= 0
                && ledger.tried.contains(candidates.get(ledger.peekIndex).pattern())) {
            ledger.peekIndex = -1;
        }
        Candidate presented = ledger.peekIndex >= 0
                ? candidates.get(ledger.peekIndex)
                : candidates.get(honest);
        // NOTE: the settle clock is NOT touched here. It belongs to the OBSERVATION, which
        // sampleWindow owns - and the room has not moved yet, so the old reading is still the
        // truth. The candidate changes what we ask the party to stand on; the clock starts when
        // they actually get there.
        ledger.presentedPattern = presented.pattern();
        int seatedNow = Math.min(seated, presented.spots().size());
        return new Plan(presented.spots(), presented.spots().size() - seatedNow, round);
    }

    /**
     * Roll one round's peek, returning the candidate it names or {@code -1}.
     *
     * <p>The answer is read only when the roll wins: an honest round must not touch it at all.
     */
    private static int peekedIndex(Character bot, Ledger ledger, List<Candidate> candidates,
                                   int round, Answer answer) {
        if (bot == null || answer == null
                || !peekWins(round, ThreadLocalRandom.current().nextDouble())) {
            return -1;
        }
        // The answer was not in the candidate space, or names one already ruled out: the honest
        // walk carries this round. The unused roll is not retried (that would inflate the chance).
        return peekedIndex(candidates, ledger.tried, answer.patternOf(bot));
    }

    /**
     * This body's spot, counted from the tail so the head slots stay free for a player.
     *
     * <p>The tail-first rule is what makes a mixed party work: the quest counts every body in
     * the instance, the real player included, so the bots must leave the spots a human is
     * likeliest to take.
     */
    public static Point mine(List<Point> spots, int bodyIndex) {
        if (bodyIndex < 0 || bodyIndex >= spots.size()) {
            return null;
        }
        return spots.get(spots.size() - 1 - bodyIndex);
    }

    private static int seatedBots(Character bot) {
        var party = bot.getParty();
        if (party == null) {
            return 1;
        }
        int bots = 0;
        for (var member : party.getMembers()) {
            if (soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage
                    .getBotById(member.getId()) != null) {
                bots++;
            }
        }
        return bots;
    }

    /**
     * The packed pattern of a mask - one body per named area, in the same nibble-per-area form
     * {@link PqActions#observedPattern} produces.
     */
    public static long maskToPattern(int mask) {
        long pattern = 0;
        for (int area = 0; area < Long.SIZE / 4; area++) {
            if ((mask & (1 << area)) != 0) {
                pattern += 1L << (area * 4);
            }
        }
        return pattern;
    }

    /** The packed pattern of a per-area body count (Amoria stage 2 and Orbis stage 4). */
    public static long countsToPattern(int... counts) {
        long pattern = 0;
        for (int area = 0; area < counts.length && area < Long.SIZE / 4; area++) {
            pattern += ((long) counts[area] & 0xF) << (area * 4);
        }
        return pattern;
    }

    /** The areas a packed pattern names, in area order - one entry per body, repeats included. */
    public static List<Integer> areasOf(long pattern) {
        List<Integer> out = new ArrayList<>();
        for (int area = 0; area < Long.SIZE / 4; area++) {
            int count = (int) ((pattern >>> (area * 4)) & 0xF);
            for (int i = 0; i < count; i++) {
                out.add(area);
            }
        }
        return out;
    }

    /** Every distribution of {@code total} bodies over {@code areas} areas, in area order. */
    public static List<long[]> countCandidates(int areas, int total) {
        List<long[]> out = new ArrayList<>();
        countInto(out, new int[areas], 0, total);
        return out;
    }

    private static void countInto(List<long[]> out, int[] counts, int at, int left) {
        if (at == counts.length - 1) {
            counts[at] = left;
            out.add(new long[]{countsToPattern(counts)});
            return;
        }
        for (int n = 0; n <= left; n++) {
            counts[at] = n;
            countInto(out, counts, at + 1, left - n);
        }
    }
}
