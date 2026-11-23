package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.extension.event.PartyQuestStageWrongEvent;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OrbisPqData;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import static soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.movementNowMs;

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
 * The search tries candidates in a fixed order and retires the presented one only when the stage
 * signals a miss and the room is standing on that pattern. Each round may also
 * peek at the answer: the first round never does, and every pattern already retired adds 0.5%
 * to the next round's chance - see {@link #peekChance} and {@link #peekedIndex}.
 *
 * <p>State is per room, not per bot: every bot in the room stands on its share of the same
 * candidate, so they must all see the same ledger.
 */
public final class PositionCombination {

    /**
     * How much each failed round adds to the next round's chance of peeking at the answer:
     * round {@code n} (1-based) peeks with probability {@code (n - 1) * 0.5%}, so the first
     * round never does and a long search keeps growing without a cap.
     */
    static final double PEEK_STEP = 0.005;

    /** One room's search: what is ruled out, what is presented, and what the stage has told us. */
    static final class Ledger {
        // Room contention must let JDK 21 bot ticks unmount from their carrier threads.
        final ReentrantLock lock = new ReentrantLock();
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
        List<Rectangle> areas;
        String resetProperty;
        long presentedPattern = -1;
        Plan presented;
        boolean announced;
        // The room shares both deadlines; individual bot ticks must not start their own timers.
        long retryChatAt;
        long retryMoveAt;
    }

    // The map owns the lifetime of the puzzle. One bot leaving must not erase the team's search.
    private static final Map<MapleMap, Ledger> LEDGERS = Collections.synchronizedMap(new WeakHashMap<>());

    /** One candidate: the packed pattern, and where each body stands. */
    public record Candidate(long pattern, List<Point> spots) {
    }

    /** The candidate the party is submitting, plus what to say about it. */
    public record Plan(List<Point> spots, int free, int attempt) {
    }

    enum PositionLine { FILL, READY, STEP_OFF, RETRY }

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

    /**
     * Take this body's puzzle spot or wait by the judge if it has none, then report the plan.
     *
     * @param bot        the body taking its share
     * @param bodyIndex  its rank among the bots, for the tail-first split
     * @param candidates every pattern the puzzle can ask for, in the order to try them
     * @param areas      the room's area rectangles, in the script's own order
     * @param resetProperty the property the stage blanks when discarding its answer, or null
     * @param answer     how to read the answer for the round's peek, or {@code null} to
     *     search honestly in every round
     * @param waitAtPost how to move an unassigned bot out of the counted areas to the NPC's post
     * @return the plan to announce, or null while the room is waiting for its next action
     */
    public static Plan standOn(Character bot, int bodyIndex, List<Candidate> candidates,
                               List<Rectangle> areas, String resetProperty,
                               Answer answer, Runnable waitAtPost) {
        Ledger ledger = ledgerFor(bot.getMap());
        // Party.getMembers() takes the host's fair lock; do not make the NPC wait on that read.
        int seated = seatedBots(bot);
        Plan plan;
        ledger.lock.lock();
        try {
            long deadline = ledger.retryChatAt != 0 ? ledger.retryChatAt : ledger.retryMoveAt;
            if (deadline != 0) {
                long remaining = deadline - movementNowMs();
                if (remaining > 0) {
                    // An earlier wheel request can win over this one. Keep the room's deadline.
                    BotSM fsm = CharacterStorage.getBotById(bot.getId());
                    if (fsm != null) fsm.requestTickWithin(remaining);
                    return null;
                }
            }
            plan = decide(ledger, bot, candidates, areas, resetProperty, seated, answer);
            if (ledger.retryChatAt != 0) return plan; // let the room speak before anyone moves
            ledger.retryMoveAt = 0;
        } finally {
            ledger.lock.unlock();
        }
        Point mine = mine(plan.spots(), bodyIndex);
        if (mine == null) {
            waitAtPost.run();
        } else {
            PqActions.holdPuzzleSpot(bot, mine, areas.get(PqActions.areaOf(mine, areas)));
        }
        return plan;
    }

    static Plan decide(Ledger ledger, Character bot, List<Candidate> candidates, List<Rectangle> areas,
                       String resetProperty, int seated, Answer answer) {
        ledger.areas = areas;
        ledger.resetProperty = resetProperty;
        return plan(ledger, candidates, seated, bot, answer);
    }

    static Ledger ledgerFor(MapleMap room) {
        return LEDGERS.computeIfAbsent(room, ignored -> new Ledger());
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

    /** Check the placement on the NPC script thread rather than guessing from dialogue timing. */
    public static void onWrong(PartyQuestStageWrongEvent event) {
        MapleMap room = event.map();
        Ledger ledger = LEDGERS.get(room);
        EventInstanceManager instance = event.instance();
        if (ledger == null || room.getEventInstance() != instance) return;
        List<Character> members;
        long chatDelay;
        ledger.lock.lock();
        try {
            if (ledger.resetProperty != null && "".equals(instance.getProperty(ledger.resetProperty))) {
                // Amoria stage 2 just discarded its answer. Previous misses no longer apply.
                resetSearch(ledger);
                if (ledger.retryChatAt != 0) return;
                members = instance.getPlayers();
            } else {
                if (ledger.presented == null || ledger.retryChatAt != 0 || ledger.retryMoveAt != 0) return;
                members = instance.getPlayers();
                if (standingPattern(room, members, ledger.areas) != ledger.presentedPattern) return;
                ledger.tried.add(ledger.presentedPattern);
                ledger.presented = null;
            }
            chatDelay = ThreadLocalRandom.current().nextLong(1_000, 2_001);
            ledger.retryChatAt = movementNowMs() + chatDelay;
            ledger.retryMoveAt = 0;
        } finally {
            ledger.lock.unlock();
        }
        wakeRoomBots(room, members, chatDelay);
    }

    private static void wakeRoomBots(MapleMap room, List<Character> members, long delay) {
        for (Character member : members) {
            if (member.getMap() != room) continue;
            BotSM bot = CharacterStorage.getBotById(member.getId());
            if ((bot instanceof PartyQuestBot || bot instanceof OPQBot) && PqStageChat.isAvailable(bot)) {
                bot.requestTickWithin(delay);
            }
        }
    }

    static void resetSearch(Ledger ledger) {
        ledger.tried.clear();
        ledger.index = 0;
        ledger.peekRound = -1;
        ledger.peekIndex = -1;
        ledger.presentedPattern = -1;
        ledger.presented = null;
    }

    private static long standingPattern(MapleMap room, List<Character> members, List<Rectangle> areas) {
        // Orbis checks the map roster; the other scripts check the event roster.
        long pattern = 0;
        if (room.getId() == OrbisPqData.STAGE_SEALED) {
            for (int area = 0; area < areas.size(); area++) {
                pattern += (long) room.getNumPlayersInArea(area) << (area * 4);
            }
            return pattern;
        }
        for (Character member : members) {
            int area = PqActions.areaOf(member.getPosition(), areas);
            if (area >= 0) pattern += 1L << (area * 4);
        }
        return pattern;
    }

    static void announcePlan(Character speaker, int stage, Plan plan, List<Rectangle> areas) {
        MapleMap room = speaker.getMap();
        Ledger ledger = LEDGERS.get(room);
        EventInstanceManager instance = speaker.getEventInstance();
        if (ledger == null || instance == null) return;
        ledger.lock.lock();
        try {
            if (ledger.presented != plan || ledger.announced) return;
            if (ledger.retryChatAt != 0) {
                if (movementNowMs() < ledger.retryChatAt || !canAnnounce(speaker, room, instance, stage)) return;
                PqStageChat.sayPositionPlan(speaker, room, stage, plan, areas, PositionLine.RETRY);
                // Time the second beat from the actual line, even if the first tick was late.
                long moveDelay = ThreadLocalRandom.current().nextLong(500, 1_501);
                ledger.retryMoveAt = movementNowMs() + moveDelay;
                ledger.retryChatAt = 0;
                ledger.announced = true;
                wakeRoomBots(room, instance.getPlayers(), moveDelay);
                return;
            }
        } finally {
            ledger.lock.unlock();
        }
        // These checks use per-bot movement locks; don't hold the room ledger while taking them.
        if (!botsInPosition(speaker, plan, areas)) return;
        ledger.lock.lock();
        try {
            if (ledger.presented != plan || ledger.announced || !canAnnounce(speaker, room, instance, stage)) return;
            boolean ready = standingPattern(room, instance.getPlayers(), areas) == ledger.presentedPattern;
            PositionLine line = ready ? PositionLine.READY
                    : plan.free() == 0 ? PositionLine.STEP_OFF : PositionLine.FILL;
            // Queue the line before a wrong effect can retire this plan and start the next move.
            PqStageChat.sayPositionPlan(speaker, room, stage, plan, areas, line);
            ledger.announced = true;
        } finally {
            ledger.lock.unlock();
        }
    }

    private static boolean canAnnounce(Character speaker, MapleMap room, EventInstanceManager instance, int stage) {
        Character leader = instance.getLeader();
        return leader != null && leader.getMap() == room && speaker.getMap() == room
                && (room.getId() == OrbisPqData.STAGE_SEALED
                ? instance.getIntProperty("statusStg4") != 1 : instance.getProperty(stage + "stageclear") == null);
    }

    private static boolean botsInPosition(Character speaker, Plan plan, List<Rectangle> areas) {
        List<Character> bots = new ArrayList<>();
        bots.add(speaker);
        BotSM self = CharacterStorage.getBotById(speaker.getId());
        var party = speaker.getParty();
        if (party != null) {
            for (var member : party.getMembers()) {
                int id = member.getId();
                if (id == speaker.getId()) continue;
                BotSM peer = eligiblePeer(speaker, self, id);
                if (peer != null) bots.add(peer.getChr());
            }
        }
        if (plan.free() != Math.max(0, plan.spots().size() - bots.size())) return false;
        bots.sort(Comparator.comparingInt(Character::getId));
        for (int i = 0; i < bots.size(); i++) {
            Character bot = bots.get(i);
            int area = PqActions.areaOf(bot.getPosition(), areas);
            if (area != PqActions.areaOf(mine(plan.spots(), i), areas)) return false;
            // A parked climber still reads as moving to the generic movement query.
            if (area >= 0 && GCMovement.isMoving(bot)
                    && !(GCMovement.isHoldingClimb(bot) && !GCMovement.hasMoveTarget(bot))) return false;
        }
        return true;
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
        int free = Math.max(0, presented.spots().size() - seated);
        if (ledger.presented == null || ledger.presentedPattern != presented.pattern()
                || ledger.presented.free() != free || ledger.presented.attempt() != round) {
            ledger.presentedPattern = presented.pattern();
            ledger.presented = new Plan(presented.spots(), free, round);
            ledger.announced = false;
        }
        return ledger.presented;
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
        BotSM self = CharacterStorage.getBotById(bot.getId());
        int bots = 1;
        for (var member : party.getMembers()) {
            int id = member.getId();
            if (id != bot.getId() && eligiblePeer(bot, self, id) != null) {
                bots++;
            }
        }
        return bots;
    }

    /** Seat indices and the free count both use the bots actually available in this room. */
    public static int botIndex(Character bot) {
        var party = bot.getParty();
        if (party == null) return 0;
        BotSM self = CharacterStorage.getBotById(bot.getId());
        int ahead = 0;
        for (var member : party.getMembers()) {
            if (member.getId() < bot.getId() && eligiblePeer(bot, self, member.getId()) != null) ahead++;
        }
        return ahead;
    }

    private static BotSM eligiblePeer(Character bot, BotSM self, int id) {
        BotSM peer = CharacterStorage.getBotById(id);
        return peer != null && (self == null || peer.getClass() == self.getClass())
                && peer.getChr().getMap() == bot.getMap() && PqStageChat.isAvailable(peer) ? peer : null;
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
