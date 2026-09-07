package soloMapling.companion.routine;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Deterministic daily routines for freshly provisioned companions.
 *
 * <p>A profile with a blank {@code routine_profile} parses to an all-offline
 * schedule, and the lifecycle coordinator only spawns companions whose schedule
 * says online — so a companion provisioned without a routine would never appear
 * in the world at all. Every intake therefore writes one, and this is what
 * writes it.</p>
 *
 * <p>The shape a real player's day has: a couple of sessions of a few hours,
 * split by gaps. Gaps are not blocks — {@link RoutineActivity#OFFLINE} is
 * reserved for them and the scheduler reads an uncovered minute as offline. So
 * "two sessions with a break" is two blocks with a hole between them, and one of
 * those holes a day is also what triggers the offline settlement.</p>
 *
 * <p>Training takes {@link #TRAIN_SHARE_MIN}–{@link #TRAIN_SHARE_MAX} of the
 * online day. A companion is a bot that levels, so that is the bulk of what it
 * is online for; the rest is what it does around the training — town, shopping,
 * chatting, resting. The share is met by time, not by block count, so a day of
 * one long grind and a short errand counts the same as two even sessions. It is
 * a band rather than an exact figure: two companions should not train for an
 * identical share of the day.</p>
 *
 * <p>Same seed in, same routine out. Provisioning already derives a companion's
 * career from {@code personaSeed}, so the day it keeps is part of the same
 * identity: restart the server and it keeps its habits rather than drawing a new
 * timetable.</p>
 */
public final class CompanionRoutineGenerator {

    /** Shortest online session worth scheduling; below this it reads as a flicker. */
    private static final int MIN_BLOCK_MINUTES = 45;
    /** Longest single session; past this a player would have logged. */
    private static final int MAX_BLOCK_MINUTES = 240;
    /** Sessions per day. Two to four keeps a day busy without filling it. */
    private static final int MIN_BLOCKS = 2;
    private static final int MAX_BLOCKS = 4;
    /**
     * Total online time per day, in minutes: 4h to 9h.
     *
     * <p>The floor is 4h because a day shorter than that cannot hold a real
     * grind: two sessions of the 45-minute minimum plus their gaps is most of
     * two hours, and a companion that shows up for an hour has no arc. Nine is
     * the ceiling because past it the day stops fitting between waking hours
     * once the gaps are in.</p>
     */
    private static final int MIN_TOTAL_MINUTES = 4 * 60;
    private static final int MAX_TOTAL_MINUTES = 9 * 60;
    /** Shortest gap between sessions. */
    private static final int MIN_GAP_MINUTES = 30;
    /** Longest single gap between sessions. */
    private static final int MAX_GAP_MINUTES = 300;
    /**
     * Share of the day's online minutes spent training.
     *
     * <p>Applied to minutes, not to sessions: a companion that trains for three
     * hours and runs an errand for one is 75% training, not 50%. A band rather
     * than one figure, so the share itself varies between companions.</p>
     */
    private static final double TRAIN_SHARE_MIN = 0.60;
    private static final double TRAIN_SHARE_MAX = 0.70;
    /**
     * Blocks never start before this or end after this. Confining the day to
     * waking hours keeps every block inside one calendar day, so no block has to
     * wrap midnight and the encoded form stays simple. The end is 23:59 rather
     * than 24:00 because that is a time a day actually contains.
     */
    private static final int DAY_START_MINUTE = 6 * 60;
    private static final int DAY_END_MINUTE = 23 * 60 + 59;

    /**
     * What a non-training session is for.
     *
     * <p>{@link RoutineActivity#SLEEP} is absent on purpose: the lifecycle
     * coordinator reads it as offline, so scheduling it would silently shorten
     * the day below the 2h floor. {@link RoutineActivity#OFFLINE} is absent
     * because the codec reserves it for gaps. {@link RoutineActivity#TRAIN} is
     * absent because the training share is met separately, by time.</p>
     */
    private static final RoutineActivity[] OTHER_ACTIVITIES = {
            RoutineActivity.TOWN,
            RoutineActivity.SHOP,
            RoutineActivity.SOCIAL,
            RoutineActivity.REST,
            RoutineActivity.TRAVEL,
    };

    private CompanionRoutineGenerator() {
    }

    /**
     * Builds the {@code v1|HH:mm-HH:mm=ACTIVITY,...} profile for one companion.
     *
     * @param personaSeed the companion's stable persona seed
     * @return a string {@link RoutineProfileCodec} accepts
     */
    public static String generate(long personaSeed) {
        Random random = new Random(personaSeed);
        double share = TRAIN_SHARE_MIN + random.nextDouble() * (TRAIN_SHARE_MAX - TRAIN_SHARE_MIN);
        int totalMinutes = MIN_TOTAL_MINUTES
                + random.nextInt(MAX_TOTAL_MINUTES - MIN_TOTAL_MINUTES + 1);
        // Cap the session count by what the day's minutes can actually hold:
        // four sessions of at least 45 minutes need three hours.
        int affordableBlocks = Math.max(MIN_BLOCKS, totalMinutes / MIN_BLOCK_MINUTES);
        int maxBlocks = Math.min(MAX_BLOCKS, affordableBlocks);
        int blockCount = MIN_BLOCKS + random.nextInt(maxBlocks - MIN_BLOCKS + 1);
        // How many of those sessions train. Proportional to the share, not a
        // free dice roll: choosing it uniformly let four sessions come out as
        // one training and three others, which no amount of stretching the
        // minutes could bring back up to 60% — those days landed near 25%.
        // Sessions still vary in length, so this sets the rough proportion and
        // the split below lands the exact figure.
        int trainCount = Math.max(1, Math.min(blockCount - 1,
                (int) Math.round(blockCount * share)));
        int wanted = (int) Math.round(totalMinutes * share);
        List<Session> sessions = plan(totalMinutes, blockCount, trainCount, wanted, random);
        List<LocalTime> starts = layOut(sessions, random);

        StringBuilder encoded = new StringBuilder(RoutineProfileCodec.VERSION_PREFIX);
        RoutineActivity previous = null;
        for (int i = 0; i < sessions.size(); i++) {
            if (i > 0) {
                encoded.append(',');
            }
            Session session = sessions.get(i);
            RoutineActivity activity = session.training()
                    ? RoutineActivity.TRAIN
                    : otherActivity(previous, random);
            previous = activity;
            RoutineBlock block = new RoutineBlock(
                    starts.get(i), starts.get(i).plusMinutes(session.minutes()), activity);
            encoded.append(format(block.start())).append('-')
                    .append(format(block.end())).append('=')
                    .append(block.activity().name());
        }
        // Round-trip through the real parser: a generator that drifted from the
        // codec would write rows that only fail later, at reconcile time, and
        // surface as a companion that mysteriously never comes online.
        RoutineProfileCodec.parse("UTC", encoded.toString());
        return encoded.toString();
    }

    /**
     * Splits the day's online minutes into sessions, spending about
     * {@link #TRAIN_SHARE} of them on training.
     *
     * <p>Training and the rest are split as two independent budgets so the share
     * is met by time. Both are then handed to {@link #share}, which keeps
     * every session inside [{@link #MIN_BLOCK_MINUTES}, {@link #MAX_BLOCK_MINUTES}]
     * by construction rather than by clamping.</p>
     *
     * <p>The share is drawn from {@link #TRAIN_SHARE_MIN}–{@link #TRAIN_SHARE_MAX}
     * and then bent into the range the chosen session counts can express. Four
     * hours online as one training session and one other session, for instance,
     * cannot be 60/40 — one session may not exceed four hours nor fall below 45
     * minutes — so it takes the closest share it can rather than producing an
     * impossible three-hour errand.</p>
     *
     * <p>There is always at least one session of each kind, so a companion is
     * never purely grinding nor purely idling. The finished list is shuffled, so
     * training is not always the first thing of the day.</p>
     */
    private static List<Session> plan(
            int totalMinutes, int blockCount, int trainCount, int wantedTrainMinutes,
            Random random) {
        int otherCount = blockCount - trainCount;
        int low = Math.max(MIN_BLOCK_MINUTES * trainCount,
                totalMinutes - MAX_BLOCK_MINUTES * otherCount);
        int high = Math.min(MAX_BLOCK_MINUTES * trainCount,
                totalMinutes - MIN_BLOCK_MINUTES * otherCount);
        int trainMinutes = Math.max(low, Math.min(high, wantedTrainMinutes));

        List<Session> sessions = new ArrayList<>();
        for (int minutes : share(trainMinutes, trainCount,
                    MIN_BLOCK_MINUTES, MAX_BLOCK_MINUTES, random)) {
            sessions.add(new Session(minutes, true));
        }
        for (int minutes : share(totalMinutes - trainMinutes, otherCount,
                    MIN_BLOCK_MINUTES, MAX_BLOCK_MINUTES, random)) {
            sessions.add(new Session(minutes, false));
        }
        Collections.shuffle(sessions, random);
        return sessions;
    }

    /**
     * Shares {@code total} out over {@code parts} shares, each inside
     * [{@code min}, {@code max}].
     *
     * <p>The bounds are honoured by construction rather than by clamping: each
     * share is drawn from the range still feasible for it, given that every
     * later share needs at least {@code min} and can absorb at most {@code max}.
     * Clamping instead would pile any excess onto the final share, so a bot
     * wanting eight hours in two sittings would get one short block and one
     * absurdly long one.</p>
     */
    private static List<Integer> share(int total, int parts, int min, int max, Random random) {
        List<Integer> shares = new ArrayList<>();
        int remaining = Math.min(total, max * parts);
        remaining = Math.max(remaining, min * parts);
        int remainingParts = parts;
        for (int i = 0; i < parts; i++) {
            if (i == parts - 1) {
                shares.add(remaining);
                break;
            }
            int low = Math.max(min, remaining - max * (remainingParts - 1));
            int high = Math.min(max, remaining - min * (remainingParts - 1));
            int value = low >= high ? low : low + random.nextInt(high - low + 1);
            shares.add(value);
            remaining -= value;
            remainingParts--;
        }
        return shares;
    }

    /**
     * Places sessions across the day, leaving a real gap before each one after
     * the first. Spare time is shared between "when the day starts" and "how long
     * the breaks are", both bounded so the run still fits the day and every gap
     * is at least {@link #MIN_GAP_MINUTES} — a companion that never logs off
     * between sessions has no offline settlement to settle.
     */
    private static List<LocalTime> layOut(List<Session> sessions, Random random) {
        int total = sessions.stream().mapToInt(Session::minutes).sum();
        int window = DAY_END_MINUTE - DAY_START_MINUTE;
        int slack = window - total;
        int gapCount = sessions.size() - 1;

        int gapTotal = 0;
        List<Integer> gaps = new ArrayList<>();
        if (gapCount > 0) {
            // Never spend so much on gaps that the day can no longer hold the
            // sessions, and never less than every gap's floor.
            int high = Math.min(slack, MAX_GAP_MINUTES * gapCount);
            int low = Math.min(high, MIN_GAP_MINUTES * gapCount);
            gapTotal = low >= high ? low : low + random.nextInt(high - low + 1);
            gaps.addAll(share(gapTotal, gapCount, MIN_GAP_MINUTES, MAX_GAP_MINUTES, random));
        }
        int lead = slack - gapTotal;
        int cursor = DAY_START_MINUTE + (lead > 0 ? random.nextInt(lead + 1) : 0);

        List<LocalTime> starts = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            if (i > 0) {
                cursor += gaps.get(i - 1);
            }
            starts.add(LocalTime.ofSecondOfDay(cursor * 60L));
            cursor += sessions.get(i).minutes();
        }
        return starts;
    }


    /**
     * A non-training activity, never the same as the session before it: two
     * consecutive sessions doing the same thing read as one session that was
     * split for no reason.
     */
    private static RoutineActivity otherActivity(RoutineActivity previous, Random random) {
        RoutineActivity pick = OTHER_ACTIVITIES[random.nextInt(OTHER_ACTIVITIES.length)];
        if (pick != previous) {
            return pick;
        }
        // Landed on the same thing twice in a row: step a non-zero distance
        // around the pool instead of re-rolling, so this terminates on the first
        // try and stays uniform over the other options.
        int index = 0;
        for (int i = 0; i < OTHER_ACTIVITIES.length; i++) {
            if (OTHER_ACTIVITIES[i] == pick) {
                index = i;
                break;
            }
        }
        int shift = 1 + random.nextInt(OTHER_ACTIVITIES.length - 1);
        return OTHER_ACTIVITIES[(index + shift) % OTHER_ACTIVITIES.length];
    }

    /** The codec's {@code HH:mm}, zero-padded — {@code LocalTime} itself prints "6:05". */
    private static String format(LocalTime value) {
        return (value.getHour() < 10 ? "0" : "") + value.getHour()
                + ":" + (value.getMinute() < 10 ? "0" : "") + value.getMinute();
    }

    /** One online session: how long it lasts, and whether it is training. */
    private record Session(int minutes, boolean training) {
    }
}
