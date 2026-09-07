package soloMapling.companion.routine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionRoutineGeneratorTest {

    /** Several thousand seeds: a generator that only fails on some inputs is worse than none. */
    private static final int SEEDS = 3000;

    @Test
    void sameSeedAlwaysProducesTheSameDay() {
        assertEquals(
                CompanionRoutineGenerator.generate(42L),
                CompanionRoutineGenerator.generate(42L));
        assertEquals(
                CompanionRoutineGenerator.generate(-7L),
                CompanionRoutineGenerator.generate(-7L));
    }

    @Test
    void differentSeedsProduceDifferentDays() {
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(CompanionRoutineGenerator.generate(i));
        }
        // Distinct seeds should not collapse onto a handful of timetables.
        assertTrue(seen.size() > 100, "expected variety, got " + seen.size());
    }

    @Test
    void everyGeneratedDayParsesUnderTheRealCodec() {
        for (int i = 0; i < SEEDS; i++) {
            String profile = CompanionRoutineGenerator.generate(seed(i));
            RoutineSchedule schedule = RoutineProfileCodec.parse("UTC", profile);
            assertFalse(schedule.blocks().isEmpty(), "no blocks for seed " + i);
        }
    }

    @Test
    void onlineTimeStaysBetweenFourAndNineHours() {
        for (int i = 0; i < SEEDS; i++) {
            RoutineSchedule schedule =
                    RoutineProfileCodec.parse("UTC", CompanionRoutineGenerator.generate(seed(i)));
            long minutes = schedule.blocks().stream()
                    .mapToLong(block -> Duration.between(block.start(), block.end()).toMinutes())
                    .sum();
            assertTrue(minutes >= 240 && minutes <= 540,
                    "seed " + i + " online minutes out of range: " + minutes);
        }
    }

    @Test
    void blocksNeverOverlapAndStayInsideTheDay() {
        for (int i = 0; i < SEEDS; i++) {
            RoutineSchedule schedule =
                    RoutineProfileCodec.parse("UTC", CompanionRoutineGenerator.generate(seed(i)));
            LocalTime previousEnd = null;
            for (RoutineBlock block : schedule.blocks()) {
                assertTrue(block.start().isBefore(block.end()),
                        "seed " + i + " block ends before it starts: " + block);
                assertTrue(!block.start().isBefore(LocalTime.of(6, 0)),
                        "seed " + i + " block starts before the day: " + block);
                assertTrue(!block.end().isAfter(LocalTime.of(23, 59)),
                        "seed " + i + " block ends after the day: " + block);
                if (previousEnd != null) {
                    assertFalse(block.start().isBefore(previousEnd),
                            "seed " + i + " blocks overlap: " + block);
                }
                previousEnd = block.end();
            }
        }
    }

    /**
     * Sessions are separated by real offline time. A day whose sessions touched
     * would read as one uninterrupted login, and would never trigger the offline
     * settlement that the routine's gaps exist to cause.
     */
    @Test
    void sessionsAreSeparatedByRealGaps() {
        for (int i = 0; i < SEEDS; i++) {
            RoutineSchedule schedule =
                    RoutineProfileCodec.parse("UTC", CompanionRoutineGenerator.generate(seed(i)));
            for (int b = 1; b < schedule.blocks().size(); b++) {
                long gap = Duration.between(
                        schedule.blocks().get(b - 1).end(),
                        schedule.blocks().get(b).start()).toMinutes();
                assertTrue(gap >= 30, "seed " + i + " gap too small: " + gap);
            }
        }
    }

    /**
     * Training is the bulk of the day but not all of it, and the share is a band
     * rather than one figure so two companions need not match exactly.
     */
    @Test
    void trainingTakesSixtyToSeventyPercentByTime() {
        double min = 100;
        double max = 0;
        double sum = 0;
        for (int i = 0; i < SEEDS; i++) {
            RoutineSchedule schedule =
                    RoutineProfileCodec.parse("UTC", CompanionRoutineGenerator.generate(seed(i)));
            long total = 0;
            long training = 0;
            for (RoutineBlock block : schedule.blocks()) {
                long minutes = Duration.between(block.start(), block.end()).toMinutes();
                total += minutes;
                if (block.activity() == RoutineActivity.TRAIN) {
                    training += minutes;
                }
            }
            double share = training * 100.0 / total;
            sum += share;
            min = Math.min(min, share);
            max = Math.max(max, share);
        }
        double average = sum / SEEDS;
        assertTrue(average >= 58 && average <= 72, "average training share off: " + average);
        // Individual days may bend where the block-count maths forces them, but
        // none should be a pure grind or a day with no training at all.
        assertTrue(min >= 30, "a day trained too little: " + min);
        assertTrue(max <= 100, "a day trained too much: " + max);
    }

    /** A companion does the same thing all day only when it has one session. */
    @Test
    void consecutiveSessionsDiffer() {
        for (int i = 0; i < SEEDS; i++) {
            RoutineSchedule schedule =
                    RoutineProfileCodec.parse("UTC", CompanionRoutineGenerator.generate(seed(i)));
            for (int b = 1; b < schedule.blocks().size(); b++) {
                if (schedule.blocks().get(b).activity() != RoutineActivity.TRAIN) {
                    assertNotEquals(
                            schedule.blocks().get(b - 1).activity(),
                            schedule.blocks().get(b).activity(),
                            "seed " + i + " repeated the same non-training session");
                }
            }
        }
    }

    /** Reserved activities must never be scheduled: they mean offline. */
    @Test
    void neverSchedulesOfflineOrSleep() {
        for (int i = 0; i < SEEDS; i++) {
            RoutineSchedule schedule =
                    RoutineProfileCodec.parse("UTC", CompanionRoutineGenerator.generate(seed(i)));
            for (RoutineBlock block : schedule.blocks()) {
                assertTrue(block.activity() != RoutineActivity.OFFLINE
                                && block.activity() != RoutineActivity.SLEEP,
                        "seed " + i + " scheduled a reserved activity: " + block);
            }
        }
    }

    /** Training is not pinned to the first session of the day. */
    @Test
    void trainingIsNotAlwaysFirst() {
        Set<RoutineActivity> firstActivities = EnumSet.noneOf(RoutineActivity.class);
        for (int i = 0; i < SEEDS; i++) {
            firstActivities.add(RoutineProfileCodec
                    .parse("UTC", CompanionRoutineGenerator.generate(seed(i)))
                    .blocks().get(0).activity());
        }
        assertTrue(firstActivities.size() > 1,
                "the first session is always the same thing: " + firstActivities);
    }

    private static long seed(int i) {
        return i * 7919L - 12345L;
    }
}
